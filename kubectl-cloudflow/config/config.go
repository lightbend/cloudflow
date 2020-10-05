package config

import (
	"errors"
	"fmt"
	"io/ioutil"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/go-akka/configuration"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/cfapp"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/fileutil"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/printutil"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"k8s.io/client-go/kubernetes"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

const cloudflowPath = "cloudflow"
const cloudflowStreamletsPath = "cloudflow.streamlets"
const cloudflowRuntimesPath = "cloudflow.runtimes"
const cloudflowTopicsPath = "cloudflow.topics"
const configParametersKey = "config-parameters"
const configKey = "config"
const kubernetesKey = "kubernetes"
const podsKey = "pods"
const cloudflowPodName = "pod"
const labels = "labels"
const volumes = "volumes"
const volumeMountsKey = "volume-mounts"
const cloudflowContainerName = "container"
const containersKey = "containers"
const resourcesKey = "resources"
const requestsKey = "requests"
const limitsKey = "limits"
const envKey = "env"
const envNameKey = "name"
const envValueKey = "value"
const taskManager = "task-manager"
const jobManager = "job-manager"

const runtimeKey = "runtime"
const runtimesKey = "runtimes"
const streamletsKey = "streamlets"

// Config keeps the configuration that has been built up so far.
type Config struct {
	builder strings.Builder
}

func newConfig(str string) *Config {
	conf := new(Config)
	conf.append(str)
	return conf
}

func (conf *Config) appendBytes(data []byte) {
	conf.builder.Write(data)
}

func (conf *Config) append(data string) {
	conf.builder.WriteString(fmt.Sprintf("%s\r\n", data))
}

func (conf *Config) isEmpty() bool {
	return conf.builder.Len() == 0
}

func (conf *Config) String() string {
	return conf.builder.String()
}

func (conf *Config) parse() *configuration.Config {

	defer func() {
		if r := recover(); r != nil {
			printutil.LogAndExit("The configuration file(s) specified are not valid.")
		}
	}()

	return configuration.ParseString(conf.String())
}

func GetAppConfiguration(
	args []string,
	namespace string,
	applicationSpec cfapp.CloudflowApplicationSpec,
	configFiles []string) (*Config, error) {

	configurationArguments, err := splitConfigurationParameters(args[1:])
	if err != nil {
		return nil, err
	}

	appConfig, err := loadAndMergeConfigs(configFiles)
	if err != nil {
		return nil, err
	}

	appConfig = replaceEnvVars(appConfig)
	appConfig = addDefaultValuesFromSpec(applicationSpec, appConfig, configurationArguments)

	appConfig = addCommandLineArguments(applicationSpec, appConfig, configurationArguments)

	if err := validateConfig(appConfig, applicationSpec); err != nil {
		return nil, err
	}

	validationError := validateConfigurationAgainstDescriptor(applicationSpec, *appConfig)
	if validationError != nil {
		return nil, validationError
	}
	return appConfig, nil
}

//LoadAndMergeConfigs loads specified configuration files and merges them into one Config
func loadAndMergeConfigs(configFiles []string) (*Config, error) {
	if len(configFiles) == 0 {
		return &Config{}, nil
	}
	config := Config{}

	// For some reason WithFallback does not work as expected, so we'll use this workaround for now.

	for _, file := range configFiles {
		if !fileutil.FileExists(file) {
			return nil, fmt.Errorf("configuration file %s passed with --conf does not exist", file)
		}
		content, err := ioutil.ReadFile(file)
		if err != nil {
			return nil, fmt.Errorf("could not read configuration file %s", file)
		}
		config.appendBytes(content)
		config.append("\r\n")
	}

	hoconConf := config.parse()

	// Maybe move validation completely to operator. The CLI can check for status. Bad side effect maybe, is that there will be incorrect resources in K8s.
	if hoconConf.GetConfig(cloudflowStreamletsPath) == nil && hoconConf.GetConfig(cloudflowRuntimesPath) == nil && hoconConf.GetConfig(cloudflowTopicsPath) == nil {
		return nil, fmt.Errorf("configuration does not contain '%s', '%s' or '%s' config sections", cloudflowStreamletsPath, cloudflowRuntimesPath, cloudflowTopicsPath)
	}
	streamletsConfig := hoconConf.GetConfig(cloudflowStreamletsPath)
	if streamletsConfig != nil && streamletsConfig.Root().IsObject() {
		for streamletName := range streamletsConfig.Root().GetObject().Items() {
			streamletConfig := streamletsConfig.GetConfig(streamletName)
			if streamletConfig != nil && streamletConfig.Root().IsObject() {
				if streamletConfig.GetConfig(configParametersKey) == nil &&
					streamletConfig.GetConfig(configKey) == nil &&
					streamletConfig.GetConfig(kubernetesKey) == nil {
					return nil, fmt.Errorf("streamlet config %s.%s does not contain '%s', '%s' or '%s' config sections", cloudflowStreamletsPath, streamletName, configParametersKey, configKey, kubernetesKey)
				}
				for streamletConfigSectionKey := range streamletConfig.Root().GetObject().Items() {
					if !(streamletConfigSectionKey == configParametersKey || streamletConfigSectionKey == configKey || streamletConfigSectionKey == kubernetesKey) {
						return nil, fmt.Errorf("streamlet config %s.%s contains unknown section '%s'", cloudflowStreamletsPath, streamletName, streamletConfigSectionKey)
					}
				}
			}
		}
	}
	return &config, nil
}

func replaceEnvVars(config *Config) *Config {
	envVars := make(map[string]string)
	for _, env := range os.Environ() {
		envPair := strings.SplitN(env, "=", 2)
		envVars[envPair[0]] = envPair[1]
	}
	if len(envVars) != 0 {
		replaced := config.String()
		for k, v := range envVars {
			replaced = strings.ReplaceAll(replaced, fmt.Sprintf("$%s", k), v)
			replaced = strings.ReplaceAll(replaced, fmt.Sprintf("${%s}", k), v)
			replaced = strings.ReplaceAll(replaced, fmt.Sprintf("${?%s}", k), v)
		}
		return newConfig(replaced)
	}
	return config
}

func validateConfig(config *Config, applicationSpec cfapp.CloudflowApplicationSpec) error {
	streamletsK8sConfigs, err := getStreamletsKubernetesConfig(config, applicationSpec)
	if err != nil {
		return err
	}
	for streamletName, k8sConfig := range streamletsK8sConfigs {
		if k8serr := validateKubernetesSection(k8sConfig, fmt.Sprintf("%s.%s", cloudflowStreamletsPath, streamletName)); k8serr != nil {
			return k8serr
		}
	}

	runtimeK8sConfigs, err := getRuntimesKubernetesConfig(config, applicationSpec)
	if err != nil {
		return err
	}
	for runtime, k8sConfig := range runtimeK8sConfigs {
		if k8serr := validateKubernetesSection(k8sConfig, fmt.Sprintf("%s.%s", cloudflowRuntimesPath, runtime)); k8serr != nil {
			return k8serr
		}
	}
	hoconConf := config.parse()
	unknownTopics := []string{}
	topicsConfig := hoconConf.GetConfig(cloudflowTopicsPath)
	if topicsConfig != nil && topicsConfig.Root().IsObject() {
		for topicID := range topicsConfig.Root().GetObject().Items() {
			foundTopic := false
			for _, deployment := range applicationSpec.Deployments {
				for _, topic := range deployment.PortMappings {
					if topic.ID == topicID {
						foundTopic = true
						break
					}
				}
			}
			if !foundTopic {
				unknownTopics = append(unknownTopics, topicID)
			}
		}
	}
	if len(unknownTopics) == 1 {
		return fmt.Errorf("Unknown topic found in configuration file: %s", strings.Join(unknownTopics, ", "))
	}

	if len(unknownTopics) > 1 {
		return fmt.Errorf("Unknown topics found in configuration file: %s", strings.Join(unknownTopics, ", "))
	}

	streamletsConfig := hoconConf.GetConfig(cloudflowStreamletsPath)
	runtimesConfig := hoconConf.GetConfig(cloudflowRuntimesPath)
	if streamletsConfig == nil && runtimesConfig == nil && topicsConfig == nil {
		return fmt.Errorf("Missing %s, %s or %s config sections", cloudflowStreamletsPath, cloudflowRuntimesPath, cloudflowTopicsPath)
	}
	return nil
}

func validateLabels(podConfig *configuration.Config, podName string) error {
	if labelsConfig := podConfig.GetConfig(labels); labelsConfig != nil && labelsConfig.Root().IsObject() {
		if podName == taskManager || podName == jobManager {
			return fmt.Errorf("'pods.%s.labels' is not allowed. Labels can NOT be applied specifically to a %s. They can only be used in a generic flink pod as 'pods.pod.labels'", podName, podName)
		}

		for key, value := range labelsConfig.Root().GetObject().Items() {
			labelKey := strings.TrimSpace(key)
			labelValue := strings.TrimSpace(value.String())

			if labelKeyHasPrefix(labelKey) {
				splitted := strings.Split(labelKey, "/")
				prefix := splitted[0]
				name := splitted[1]
				if err := validateLabelPrefix(prefix, labelKey); err != nil {
					return err
				}
				if err := validateLabelName(name); err != nil {
					return err
				}
				return validateLabelValue(labelValue, labelKey)
			}

			name := labelKey
			if err := validateLabelName(name); err != nil {
				return err
			}
			return validateLabelValue(labelValue, name)
		}
	}
	return nil
}

func labelKeyHasPrefix(label string) bool {
	return strings.Count(label, "/") == 1 && !strings.HasPrefix(label, "/") && !strings.HasSuffix(label, "/")
}

//Only the prefix of a value key has a different check
func validateLabelPrefix(prefix string, labelKey string) error {
	//the prefix must be a DNS subdomain. As per https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#syntax-and-character-set
	labelPrefixPattern := regexp.MustCompile(`^[a-z0-9\.]{0,252}[a-z0-9]{0,1}$`)
	labelSingleCharFormat := regexp.MustCompile(`^[a-zA-Z]{1}$`)
	illegalLabelPrefixPattern := regexp.MustCompile(`^[0-9\-]`)
	malformedLabelMsg := "label '%s' is malformed. Please review the constraints at https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#syntax-and-character-set"

	if len(prefix) > 0 && illegalLabelPrefixPattern.MatchString(prefix) {
		return fmt.Errorf(malformedLabelMsg, fmt.Sprintf("%s/%s", prefix, labelKey))
	}
	if labelPrefixPattern.MatchString(prefix) || labelSingleCharFormat.MatchString(prefix) {
		return nil
	}
	return fmt.Errorf("The value of label %s is malformed: '%s'. Please review the constraints at https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#syntax-and-character-set", labelKey, prefix)
}

func validateLabelName(label string) error {
	labelNamePattern := regexp.MustCompile(`^[a-z0-9A-Z]{1}[a-z0-9A-Z\.\_\-]{0,61}[a-z0-9A-Z]{1}$`)
	labelSingleCharFormat := regexp.MustCompile(`^[a-z0-9A-Z]{1}$`)
	// check for HOCON error that is not caught by go/akka library
	if strings.ContainsAny(label, "{") || len(label) == 0 {
		return fmt.Errorf("label name '%s' is invalid", label)
	}
	if labelNamePattern.MatchString(label) || labelSingleCharFormat.MatchString(label) {
		return nil
	}
	return fmt.Errorf("label %s is malformed. Please review the constraints at https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#syntax-and-character-set", label)
}

func validateLabelValue(labelValue string, label string) error {
	labelValuePattern := regexp.MustCompile(`^[a-z0-9A-Z]{1}[a-z0-9A-Z\.\_\-]{0,61}[a-z0-9A-Z]{1}$`)
	labelSingleCharFormat := regexp.MustCompile(`^[a-z0-9A-Z]{1}$`)
	// check for HOCON error that is not caught by go/akka library
	if strings.ContainsAny(labelValue, "{") || len(labelValue) == 0 {
		return fmt.Errorf("label '%s' has a value that can't be parsed: '%s'", label, labelValue)
	}
	if labelValuePattern.MatchString(labelValue) || labelSingleCharFormat.MatchString(labelValue) {
		return nil
	}
	return fmt.Errorf("The value of label %s is malformed: '%s'. Please review the constraints at https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#syntax-and-character-set", label, labelValue)
}

func validateVolumes(podConfig *configuration.Config) error {
	if volumesConfig := podConfig.GetConfig(volumes); volumesConfig != nil && volumesConfig.Root().IsObject() {
		for key, value := range volumesConfig.Root().GetObject().Items() {
			secret := value.GetObject().GetKey("secret")
			pvc := value.GetObject().GetKey("pvc")
			if secret == nil && pvc == nil {
				return fmt.Errorf("missing or malformed  volume %s. 'pvc' or 'secret' expected", key)
			}
			if secret != nil && !secret.IsObject() {
				return fmt.Errorf("missing or malformed 'secret' in volume %s. Please have a look at documentation to see what's expected", key)
			}
			if pvc != nil && !pvc.IsObject() {
				return fmt.Errorf("missing or malformed 'pvc' in volume %s. Please have a look at documentation to see what's expected", key)
			}
			if secret != nil {
				name := secret.GetObject().GetKey("name")
				if name == nil {
					return fmt.Errorf("missing 'name' in %s.secret", key)
				}
				if name.IsEmpty() {
					return fmt.Errorf("missing content of 'name' in %s.secret.name", key)
				}
			} else if pvc != nil {
				name := pvc.GetObject().GetKey("name")
				if name == nil {
					return fmt.Errorf("missing 'name' in %s.pvc", key)
				}
				if name.IsEmpty() {
					return fmt.Errorf("missing content of 'name' in %s.pvc.name", key)
				}
				readOnly := pvc.GetObject().GetKey("read-only")
				if readOnly == nil {
					return fmt.Errorf("missing 'read-only' in %s.pvc", key)
				}
				if readOnly.IsEmpty() {
					return fmt.Errorf("missing content of 'read-only' in %s.pvc.read-only", key)
				}
			}

		}
	}
	return nil
}

func validateVolumesMounts(containersConfig *configuration.Config) error {
	allowedProperties := []string{"mount-path", "read-only", "subPath"}
	for containerName := range containersConfig.Root().GetObject().Items() {
		if containerConfig := containersConfig.GetConfig(containerName); containerConfig != nil && containerConfig.Root().IsObject() {
			if volumesMountsConfig := containerConfig.GetConfig(volumeMountsKey); volumesMountsConfig != nil && volumesMountsConfig.Root().IsObject() {
				for volumeMountName, volumeMountKeyValue := range volumesMountsConfig.Root().GetObject().Items() {
					for key, value := range volumeMountKeyValue.GetObject().Items() {
						if !contains(allowedProperties, key) {
							return fmt.Errorf("not allowed '%s' key in the volume-mounts '%s'. Properties allowed are: %s ", key, volumeMountName, allowedProperties)
						}
						if value.IsEmpty() {
							return fmt.Errorf("key '%s' has not value in then volume-mounts '%s'", key, volumeMountName)
						}
					}
				}
			}
		}
	}
	return nil
}

func checkVolumeMountsReferToVolume(podsConfig *configuration.Config, containersConfig *configuration.Config) error {

	validateVolumes(podsConfig.GetConfig("pod"))
	validateVolumesMounts(containersConfig)

	var volumesNames []string
	var volumeMountSecretNames []string
	if volumesConfig := podsConfig.GetConfig("pod").GetConfig(volumes); volumesConfig != nil && volumesConfig.Root().IsObject() {
		for volumeName := range volumesConfig.Root().GetObject().Items() {
			volumesNames = append(volumesNames, volumeName)
		}
	}
	for containerName := range containersConfig.Root().GetObject().Items() {
		if containerConfig := containersConfig.GetConfig(containerName); containerConfig != nil && containerConfig.Root().IsObject() {
			if volumesMountsConfig := containerConfig.GetConfig(volumeMountsKey); volumesMountsConfig != nil && volumesMountsConfig.Root().IsObject() {
				for volumeMountName := range volumesMountsConfig.Root().GetObject().Items() {
					if !contains(volumesNames, volumeMountName) {
						return fmt.Errorf("the volume-mounts '%s' should match a volume.secret.name in '%s'", volumeMountName, volumesNames)
					}
					volumeMountSecretNames = append(volumeMountSecretNames, volumeMountName)
				}
			}
		}
	}
	return nil
}

func contains(s []string, e string) bool {
	for _, a := range s {
		if a == e {
			return true
		}
	}
	return false
}

func validateKubernetesSection(k8sConfig *configuration.Config, rootPath string) error {
	if podsConfig := k8sConfig.GetConfig(podsKey); podsConfig != nil && podsConfig.Root().IsObject() {
		for podName := range podsConfig.Root().GetObject().Items() {
			if podConfig := podsConfig.GetConfig(podName); podConfig != nil && podConfig.Root().IsObject() {
				if err := validateLabels(podConfig, podName); err != nil {
					return err
				}
				if err := validateVolumes(podConfig); err != nil {
					return err
				}

				containersConfig := podConfig.GetConfig(containersKey)

				if containersConfig == nil && podConfig.GetConfig(labels) == nil && podConfig.GetConfig(volumes) == nil {
					return fmt.Errorf("kubernetes configuration %s.%s.%s.%s for pod '%s' does not contain a %s section nor labels",
						rootPath,
						kubernetesKey,
						podsKey,
						podName,
						podName,
						containersKey)
				}

				if containersConfig != nil && containersConfig.Root().IsObject() {
					if err := validateVolumesMounts(containersConfig); err != nil {
						return err
					}
					if err := checkVolumeMountsReferToVolume(podsConfig, containersConfig); err != nil {
						return err
					}
					for containerName := range containersConfig.Root().GetObject().Items() {
						if containerConfig := containersConfig.GetConfig(containerName); containerConfig != nil && containerConfig.Root().IsObject() {
							for containerKey := range containerConfig.Root().GetObject().Items() {
								if !(containerKey == resourcesKey || containerKey == envKey || containerKey == volumeMountsKey) {
									return fmt.Errorf("kubernetes configuration for pod '%s', container '%s' at %s.%s.%s.%s.%s.%s does not contain a %s or an %s section",
										podName,
										containerName,
										rootPath,
										kubernetesKey,
										podsKey,
										podName,
										containersKey,
										containerName,
										resourcesKey,
										envKey)
								}
								if containerKey == resourcesKey {
									if resourcesConfig := containerConfig.GetConfig(resourcesKey); resourcesConfig != nil && resourcesConfig.Root().IsObject() {
										for resourceRequirementKey := range resourcesConfig.Root().GetObject().Items() {
											if !(resourceRequirementKey == requestsKey || resourceRequirementKey == limitsKey) {
												return fmt.Errorf("kubernetes configuration for pod '%s', container '%s' at %s.%s.%s.%s.%s.%s.%s does not contain a %s or a %s section",
													podName,
													containerName,
													rootPath,
													kubernetesKey,
													podsKey,
													podName,
													containersKey,
													containerName,
													resourcesKey,
													requestsKey,
													limitsKey)
											}

										}
									} else {
										return fmt.Errorf("kubernetes configuration for pod '%s', container '%s', resources section is missing at %s.%s.%s.%s.%s.%s.%s. The resources section should contain %s and/or %s sections",
											podName,
											containerName,
											rootPath,
											kubernetesKey,
											podsKey,
											podName,
											containersKey,
											containerName,
											resourcesKey,
											requestsKey,
											limitsKey)
									}
								}
								if containerKey == envKey {
									if containerConfig.IsArray(envKey) {
										for i, envElement := range containerConfig.GetConfig(envKey).Root().GetArray() {
											if envElement.GetObject() != nil {
												for envObjectKey := range envElement.GetObject().Items() {
													if !(envObjectKey == envNameKey || envObjectKey == envValueKey) {
														return fmt.Errorf("kubernetes configuration for pod '%s', container '%s' at %s.%s.%s.%s.%s.%s.%s array contains a value at (%d) that is not an environment variables name/value object, unknown key %s",
															podName,
															containerName,
															rootPath,
															kubernetesKey,
															podsKey,
															podName,
															containersKey,
															containerName,
															envKey,
															i,
															envObjectKey)
													}
												}
											} else {
												return fmt.Errorf("kubernetes configuration for pod '%s', container '%s' at %s.%s.%s.%s.%s.%s.%s array contains a value at (%d) that is not an environment variables name/value object %s",
													podName,
													containerName,
													rootPath,
													kubernetesKey,
													podsKey,
													podName,
													containersKey,
													containerName,
													envKey,
													i,
													envElement,
												)
											}
										}
									} else {
										return fmt.Errorf("kubernetes configuration for pod '%s', container '%s' at %s.%s.%s.%s.%s.%s.%s is not an environment variables array",
											podName,
											containerName,
											rootPath,
											kubernetesKey,
											podsKey,
											podName,
											containersKey,
											containerName,
											envKey)
									}
								}
							}
						} else {
							return fmt.Errorf("kubernetes configuration for pod '%s', container '%s' at %s.%s.%s.%s.%s.%s is not a container section",
								podName,
								containerName,
								rootPath,
								kubernetesKey,
								podsKey,
								podName,
								containersKey,
								containerName)
						}
					}
				}
			} else {
				return fmt.Errorf("kubernetes configuration %s.%s.%s does not contain a pod section. The pod section should be at %s.%s.%s.pod",
					rootPath,
					kubernetesKey,
					podsKey,
					rootPath,
					kubernetesKey,
					podsKey)
			}
		}
	} else {
		return fmt.Errorf("kubernetes configuration %s.%s does not contain a '%s' section. The pods sections should be at %s.%s.%s",
			rootPath,
			kubernetesKey,
			podsKey,
			rootPath,
			kubernetesKey,
			podsKey)
	}
	return nil
}

func addDefaultValuesFromSpec(applicationSpec cfapp.CloudflowApplicationSpec, config *Config, configurationArguments map[string]string) *Config {
	hoconConf := config.parse()
	for _, streamlet := range applicationSpec.Streamlets {
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			fqKey := prefixConfigParameterKey(streamlet.Name, descriptor.Key)
			_, providedByArgs := configurationArguments[fqKey]
			if !hoconConf.HasPath(fqKey) && !providedByArgs {
				fmt.Printf("Default value '%s' will be used for configuration parameter '%s'\n", descriptor.DefaultValue, fqKey)
				config.append(fmt.Sprintf("%s=\"%s\"", fqKey, descriptor.DefaultValue))
			}
		}
	}
	return config
}

func addCommandLineArguments(spec cfapp.CloudflowApplicationSpec, config *Config, configurationArguments map[string]string) *Config {
	written := make(map[string]string)
	for _, streamlet := range spec.Streamlets {
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			key := prefixConfigParameterKey(streamlet.Name, descriptor.Key)
			configValue := configurationArguments[key]
			if configValue != "" {
				config.append(fmt.Sprintf("%s=\"%s\"", key, configValue))
				written[key] = configValue
			}
		}
	}

	for key, configValue := range configurationArguments {
		if _, ok := written[key]; !ok {
			config.append(fmt.Sprintf("%s=\"%s\"", key, configValue))
		}
	}
	return config
}

func CreateAppInputSecret(spec *cfapp.CloudflowApplicationSpec, config *Config) (*corev1.Secret, error) {
	secretMap := make(map[string]string)
	secretMap["secret.conf"] = config.String()
	secret := CreateInputSecret(spec.AppID, secretMap)
	return secret, nil
}

// UpdateSecretWithOwnerReference updates the secret with the ownerreference passed in
func UpdateSecretWithOwnerReference(cloudflowCROwnerReference metav1.OwnerReference, secret *corev1.Secret) *corev1.Secret {
	secret.ObjectMeta.OwnerReferences = []metav1.OwnerReference{cloudflowCROwnerReference}
	return secret
}

func findSecretName(spec *cfapp.CloudflowApplicationSpec, streamletName string) string {
	for _, deployment := range spec.Deployments {
		if deployment.StreamletName == streamletName {
			return deployment.SecretName
		}
	}
	panic(fmt.Errorf("could not find secret name for streamlet %s", streamletName))
}

func CreateInputSecret(appID string, data map[string]string) *corev1.Secret {
	labels := cfapp.CreateLabels(appID)
	labels["com.lightbend.cloudflow/app-id"] = appID
	labels["com.lightbend.cloudflow/created-at"] = fmt.Sprintf("%d", time.Now().UnixNano())
	// indicates the secret contains cloudflow config format
	labels["com.lightbend.cloudflow/config-format"] = "input"
	secret := &corev1.Secret{
		Type: corev1.SecretTypeOpaque,
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-%s", "config", appID),
			Namespace: appID,
			Labels:    labels,
		},
	}
	secret.StringData = data
	return secret
}

func runtimeConfigKey(runtime string) string {
	return fmt.Sprintf("%s.%s.%s", cloudflowRuntimesPath, runtime, configKey)
}

func streamletConfigKey(streamletName string) string {
	return fmt.Sprintf("%s.%s", cloudflowStreamletsPath, streamletName)
}

func streamletRuntimeConfigKey(streamletName string) string {
	return fmt.Sprintf("%s.%s.%s", cloudflowStreamletsPath, streamletName, configKey)
}

func configParametersPrefix(streamletName string) string {
	return fmt.Sprintf("%s.%s.%s", cloudflowStreamletsPath, streamletName, configParametersKey)
}

func prefixConfigParameterKey(streamletName string, key string) string {
	return fmt.Sprintf("%s.%s", configParametersPrefix(streamletName), key)
}

// splitConfigurationParameters maps string representations of a key/value pair into a map
func splitConfigurationParameters(configurationParameters []string) (map[string]string, error) {
	configurationKeyValues := make(map[string]string)

	for _, v := range configurationParameters {
		keyValueArray, err := splitOnFirstCharacter(v, '=')
		if err != nil {
			return map[string]string{}, err
		}
		configurationKeyValues[keyValueArray[0]] = keyValueArray[1]
	}
	return configurationKeyValues, nil
}

func splitOnFirstCharacter(str string, char byte) ([]string, error) {
	var arr []string
	if idx := strings.IndexByte(str, char); idx >= 0 {
		arr = append(arr, str[:idx])
		arr = append(arr, strings.Trim(str[idx+1:], "\""))
		return arr, nil
	}
	return arr, fmt.Errorf("the configuration parameters must be formated as space delimited '[config-path]=[value]' pairs, where [config-path] is for instance 'cloudflow.streamlets.[streamlet-name].config-parameters.[property]'")
}

// validateConfigurationAgainstDescriptor validates all configuration values against configuration parameter descriptors
func validateConfigurationAgainstDescriptor(spec cfapp.CloudflowApplicationSpec, config Config) error {

	type ValidationErrorDescriptor struct {
		FqKey              string
		ProblemDescription string
	}

	var missingKeys []ValidationErrorDescriptor
	var invalidKeys []ValidationErrorDescriptor

	hoconConf := config.parse()
	for _, streamlet := range spec.Streamlets {
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			streamletConfigKey := prefixConfigParameterKey(streamlet.Name, descriptor.Key)
			fqKey := streamletConfigKey
			if err := validateStreamletConfigValue(descriptor, hoconConf.GetString(fqKey)); err != nil {
				invalidKeys = append(invalidKeys, ValidationErrorDescriptor{streamletConfigKey, err.Error()})
			}

			if hoconConf.GetString(fqKey) == "" && descriptor.DefaultValue != "" {
				missingKeys = append(missingKeys, ValidationErrorDescriptor{streamletConfigKey, descriptor.Description})
			}
		}
	}

	var str strings.Builder
	if len(missingKeys) > 0 {
		str.WriteString("Please provide values for the following configuration parameter(s):\n")
		for i := range missingKeys {
			str.WriteString(fmt.Sprintf("- %s - %s\n", missingKeys[i].FqKey, missingKeys[i].ProblemDescription))
		}
		return errors.New(str.String())
	}
	if len(invalidKeys) > 0 {
		str.WriteString("The following configuration parameter(s) have failed to validate:\n")
		for i := range invalidKeys {
			str.WriteString(fmt.Sprintf("- %s - %s\n", invalidKeys[i].FqKey, invalidKeys[i].ProblemDescription))
		}
		return errors.New(str.String())
	}
	return nil
}

func validateStreamletConfigValue(descriptor cfapp.ConfigParameterDescriptor, value string) error {
	switch descriptor.Type {

	case "bool":
		if value != "true" && value != "false" &&
			value != "on" && value != "off" &&
			value != "yes" && value != "no" {
			return fmt.Errorf("value `%s`is not a valid boolean. A boolean must be one of the following textual values `true','false',`yes`,`no`,`on` or `off`", value)
		}
	case "int32":
		_, err := strconv.ParseInt(value, 10, 32)
		if err != nil {
			return fmt.Errorf("value `%s` is not a valid integer", value)
		}
	case "double":
		_, err := strconv.ParseFloat(value, 64)
		if err != nil {
			return fmt.Errorf("value `%s` is not a valid double", value)
		}
	case "string":
		r, err := regexp.Compile(descriptor.Pattern)
		if err != nil {
			return fmt.Errorf("the regular expression pattern failed to compile: %s", err.Error())
		}

		if !r.MatchString(value) {
			return fmt.Errorf("value `%s` does not match the regular expression `%s`", value, descriptor.Pattern)
		}
	case "duration":
		if err := validateDuration(value); err != nil {
			return err
		}
	case "memorysize":
		if err := validateMemorySize(value); err != nil {
			return err
		}
	default:
		return fmt.Errorf("encountered an unknown validation type `%s`. Please make sure that the CLI is up-to-date", descriptor.Type)
	}

	return nil
}

func validateConfigParameterFormat(value string) ([]string, error) {
	split := make([]string, 0)
	for i, r := range value {
		if !unicode.IsDigit(r) {
			first := strings.TrimSpace(string(value[:i]))
			second := strings.TrimSpace(string(value[i:]))
			if len(first) != 0 {
				split = append(split, first)
			}
			if len(second) != 0 {
				split = append(split, second)
			}
			break
		}
	}
	if len(split) != 2 {
		return split, fmt.Errorf("the string '%s' is not a valid", value)
	}

	return split, nil
}

func validateConfigParameterUnits(unit string, validUnits []string) error {

	for _, v := range validUnits {
		if v == unit {
			return nil
		}
	}

	return fmt.Errorf("unit '%s' is not recognized", unit)
}

// validateDuration validates a Typesafe config duration
func validateDuration(value string) error {

	// NOTE ! Duration defaults to `ms` if there is no unit attached to the value
	// Check here if the string lacks unit, in that case append `ms` and continue
	// validation after that.
	if i, convErr := strconv.Atoi(value); convErr == nil {
		value = fmt.Sprintf("%d ms", i)
	}

	split, err := validateConfigParameterFormat(value)
	if err != nil {
		return fmt.Errorf("value `%s` is not a valid duration", value)
	}

	units := []string{
		"ns", "nano", "nanos", "nanosecond", "nanoseconds",
		"us", "micro", "micros", "microsecond", "microseconds",
		"ms", "milli", "millis", "millisecond", "milliseconds",
		"s", "second", "seconds",
		"m", "minute", "minutes",
		"h", "hour", "hours",
		"d", "day", "days",
	}

	uniterr := validateConfigParameterUnits(split[1], units)
	if uniterr != nil {
		return uniterr
	}
	return nil
}

// validateMemorySize validates Typesafe config notation of memory size
func validateMemorySize(value string) error {

	split, err := validateConfigParameterFormat(value)
	if err != nil {
		return fmt.Errorf("value `%s` is not a valid memory size", value)
	}

	units := []string{
		"B", "b", "byte", "bytes",
		"kB", "kilobyte", "kilobytes",
		"MB", "megabyte", "megabytes",
		"GB", "gigabyte", "gigabytes",
		"TB", "terabyte", "terabytes",
		"PB", "petabyte", "petabytes",
		"EB", "exabyte", "exabytes",
		"ZB", "zettabyte", "zettabytes",
		"YB", "yottabyte", "yottabytes",
		"K", "k", "Ki", "KiB", "kibibyte", "kibibytes",
		"M", "m", "Mi", "MiB", "mebibyte", "mebibytes",
		"G", "g", "Gi", "GiB", "gibibyte", "gibibytes",
		"T", "t", "Ti", "TiB", "tebibyte", "tebibytes",
		"P", "p", "Pi", "PiB", "pebibyte", "pebibytes",
		"E", "e", "Ei", "EiB", "exbibyte", "exbibytes",
		"Z", "z", "Zi", "ZiB", "zebibyte", "zebibytes",
		"Y", "y", "Yi", "YiB", "yobibyte", "yobibytes",
	}

	uniterr := validateConfigParameterUnits(split[1], units)
	if uniterr != nil {
		return uniterr
	}
	return nil
}

func getPVCs(namespace string, clientset *kubernetes.Clientset) (*corev1.PersistentVolumeClaimList, error) {
	pvcClient := clientset.CoreV1().PersistentVolumeClaims(namespace)
	return pvcClient.List(metav1.ListOptions{})
}

func existsPVC(pvc string, pvcs *corev1.PersistentVolumeClaimList, namespace string) error {
	for _, item := range pvcs.Items {
		if item.ObjectMeta.Name == pvc {
			return nil
		}
	}
	return fmt.Errorf("pvc '%s' is not present in the namespace '%s'", pvc, namespace)
}

func k8sConfigPVCsExist(k8sConfig *configuration.Config, namespace string, k8sClient *kubernetes.Clientset) error {
	if podsConfig := k8sConfig.GetConfig(podsKey); podsConfig != nil && podsConfig.Root().IsObject() {
		for podName := range podsConfig.Root().GetObject().Items() {
			if podConfig := podsConfig.GetConfig(podName); podConfig != nil && podConfig.Root().IsObject() {
				if volumesConfig := podConfig.GetConfig(volumes); volumesConfig != nil && volumesConfig.Root().IsObject() {
					for _, value := range volumesConfig.Root().GetObject().Items() {
						pvc := value.GetObject().GetKey("pvc")
						if pvc != nil {
							pvcName := pvc.GetObject().GetKey("name")
							if pvcName != nil {
								pvcs, err := getPVCs(namespace, k8sClient)
								if err != nil {
									return err
								}
								if err := existsPVC(pvcName.String(), pvcs, namespace); err != nil {
									return err
								}
							}
						}

					}
				}
			}
		}
	}
	return nil
}

// ReferencedPersistentVolumeClaimsExist checks if all the PVCs exist.
func ReferencedPersistentVolumeClaimsExist(config *Config, namespace string, applicationSpec cfapp.CloudflowApplicationSpec, k8sClient *kubernetes.Clientset) error {
	streamletsK8sConfigMap, err := getStreamletsKubernetesConfig(config, applicationSpec)
	if err != nil {
		return err
	}
	for _, k8sConfig := range streamletsK8sConfigMap {
		if err = k8sConfigPVCsExist(k8sConfig, namespace, k8sClient); err != nil {
			return err
		}
	}

	runtimesK8sConfigMap, err := getRuntimesKubernetesConfig(config, applicationSpec)
	if err != nil {
		return err
	}
	for _, k8sConfig := range runtimesK8sConfigMap {
		if err = k8sConfigPVCsExist(k8sConfig, namespace, k8sClient); err != nil {
			return err
		}
	}
	return nil
}

func getStreamletsKubernetesConfig(config *Config, applicationSpec cfapp.CloudflowApplicationSpec) (map[string]*configuration.Config, error) {
	//map of name of the runtime and its config
	k8sConfigs := make(map[string]*configuration.Config)

	if config.isEmpty() {
		return k8sConfigs, nil
	}

	hoconConf := config.parse()
	cloudflowConfig := hoconConf.GetConfig(cloudflowPath)
	if cloudflowConfig != nil && cloudflowConfig.Root().IsObject() {
		for section := range cloudflowConfig.Root().GetObject().Items() {
			absPath := fmt.Sprintf("%s.%s", cloudflowPath, section)
			if !(absPath == cloudflowStreamletsPath || absPath == cloudflowRuntimesPath || absPath == cloudflowTopicsPath) {
				return k8sConfigs, fmt.Errorf("Unknown configuration path '%s'", absPath)
			}
		}
	} else {
		return k8sConfigs, fmt.Errorf("Configuration misses root '%s' section", cloudflowPath)
	}

	streamletsConfig := hoconConf.GetConfig(cloudflowStreamletsPath)
	if streamletsConfig != nil && streamletsConfig.Root().IsObject() {
		for streamletName := range streamletsConfig.Root().GetObject().Items() {
			streamletFound := false
			foundInstance := cfapp.Streamlet{}
			for _, streamletInstance := range applicationSpec.Streamlets {
				if streamletInstance.Name == streamletName {
					streamletFound = true
					foundInstance = streamletInstance
					break
				}
			}
			if !streamletFound {
				return k8sConfigs, fmt.Errorf("Configuration contains unknown streamlet '%s'", streamletName)
			}

			streamletConfig := streamletsConfig.GetConfig(streamletName)
			if streamletConfig != nil && streamletConfig.Root().IsObject() {
				if streamletConfig.GetConfig(configParametersKey) == nil &&
					streamletConfig.GetConfig(configKey) == nil &&
					streamletConfig.GetConfig(kubernetesKey) == nil {
					return k8sConfigs, fmt.Errorf("streamlet config in path '%s.%s' does not contain '%s', '%s' or '%s' config sections", cloudflowStreamletsPath, streamletName, configParametersKey, configKey, kubernetesKey)
				}
				for streamletConfigSectionKey := range streamletConfig.Root().GetObject().Items() {
					if !(streamletConfigSectionKey == configParametersKey || streamletConfigSectionKey == configKey || streamletConfigSectionKey == kubernetesKey) {
						return k8sConfigs, fmt.Errorf("streamlet config in path '%s.%s' contains unknown section '%s'", cloudflowStreamletsPath, streamletName, streamletConfigSectionKey)
					}
					if streamletConfigSectionKey == configParametersKey {
						if configParametersSection := streamletConfig.GetConfig(configParametersKey); configParametersSection != nil && configParametersSection.Root().IsObject() {
							for configParKey := range configParametersSection.Root().GetObject().Items() {
								configParFound := false
								for _, configParameterDescriptor := range foundInstance.Descriptor.ConfigParameters {
									if configParameterDescriptor.Key == configParKey {
										configParFound = true
										break
									}
								}
								if !configParFound {
									return k8sConfigs, fmt.Errorf("Unknown config parameter '%s' found for streamlet '%s' in path '%s.%s.%s'", configParKey, streamletName, cloudflowStreamletsPath, streamletName, configParametersKey)
								}
							}
						}
					}
					if streamletConfigSectionKey == kubernetesKey {
						if k8sConfig := streamletConfig.GetConfig(kubernetesKey); k8sConfig != nil && k8sConfig.Root().IsObject() {
							k8sConfigs[streamletName] = k8sConfig
						} else {
							return k8sConfigs, fmt.Errorf("streamlet kubernetes config in path '%s.%s.%s' is not a valid %s section", cloudflowStreamletsPath, streamletName, kubernetesKey, kubernetesKey)
						}
					}
				}
			}
		}
	}
	return k8sConfigs, nil
}

func getRuntimesKubernetesConfig(config *Config, applicationSpec cfapp.CloudflowApplicationSpec) (map[string]*configuration.Config, error) {
	//map of name of the runtime and its config
	k8sConfigs := make(map[string]*configuration.Config)

	if config.isEmpty() {
		return k8sConfigs, nil
	}

	hoconConf := config.parse()
	cloudflowConfig := hoconConf.GetConfig(cloudflowPath)
	if cloudflowConfig != nil && cloudflowConfig.Root().IsObject() {
		for section := range cloudflowConfig.Root().GetObject().Items() {
			absPath := fmt.Sprintf("%s.%s", cloudflowPath, section)
			if !(absPath == cloudflowStreamletsPath || absPath == cloudflowRuntimesPath || absPath == cloudflowTopicsPath) {
				return k8sConfigs, fmt.Errorf("Unknown configuration path '%s'", absPath)
			}
		}
	} else {
		return k8sConfigs, fmt.Errorf("Configuration misses root '%s' section", cloudflowPath)
	}
	runtimesConfig := hoconConf.GetConfig(cloudflowRuntimesPath)
	if runtimesConfig != nil && runtimesConfig.Root().IsObject() {
		for runtime := range runtimesConfig.Root().GetObject().Items() {
			runtimeConfig := runtimesConfig.GetConfig(runtime)
			if runtimeConfig != nil && runtimeConfig.Root().IsObject() {
				if runtimeConfig.GetConfig(configKey) == nil &&
					runtimeConfig.GetConfig(kubernetesKey) == nil {
					return k8sConfigs, fmt.Errorf("runtime config %s.%s does not contain '%s' or '%s' config sections", cloudflowRuntimesPath, runtime, configKey, kubernetesKey)
				}
				for runtimeConfigKey := range runtimeConfig.Root().GetObject().Items() {
					if !(runtimeConfigKey == configKey || runtimeConfigKey == kubernetesKey) {
						return k8sConfigs, fmt.Errorf("streamlet config %s.%s contains unknown section '%s'", cloudflowRuntimesPath, runtime, runtimeConfigKey)
					}
				}
				if k8sConfig := runtimeConfig.GetConfig(kubernetesKey); k8sConfig != nil && k8sConfig.Root().IsObject() {
					k8sConfigs[runtime] = k8sConfig
				}
			}
		}
	}
	return k8sConfigs, nil
}
