package deploy

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/ioutil"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/docker"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/rayroestenburg/configuration"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"k8s.io/client-go/kubernetes"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

const cloudflowStreamletsPrefix = "cloudflow.streamlets."

// GetCloudflowApplicationDescriptorFromDockerImage pulls a image and extracts the Cloudflow Application descriptor from a docker label
func GetCloudflowApplicationDescriptorFromDockerImage(dockerRegistryURL string, dockerRepository string, dockerImagePath string) (domain.CloudflowApplicationSpec, docker.PulledImage) {

	apiversion, apierr := exec.Command("docker", "version", "--format", "'{{.Server.APIVersion}}'").Output()
	if apierr != nil {
		util.LogAndExit("Could not get docker API version, is the docker daemon running? API error: %s", apierr.Error())
	}

	trimmedapiversion := strings.Trim(string(apiversion), "\t \n\r'")
	client, error := docker.GetClient(trimmedapiversion)
	if error != nil {
		client, error = docker.GetClient("1.39")
		if error != nil {
			fmt.Printf("No compatible version of the Docker server API found, tried version %s and 1.39", trimmedapiversion)
			panic(error)
		}
	}

	pulledImage, pullError := docker.PullImage(client, dockerImagePath)
	if pullError != nil {
		util.LogAndExit("Failed to pull image %s: %s", dockerImagePath, pullError.Error())
	}

	applicationDescriptorImageDigest := docker.GetCloudflowApplicationDescriptor(client, dockerImagePath)

	var spec domain.CloudflowApplicationSpec
	marshalError := json.Unmarshal([]byte(applicationDescriptorImageDigest.AppDescriptor), &spec)
	if marshalError != nil {
		fmt.Print("\n\nAn unexpected error has occurred, please contact support and include the information below.\n\n")
		panic(marshalError)
	}

	if spec.Version != domain.SupportedApplicationDescriptorVersion {
		// If the version is an int, compare them, otherwise provide a more general message.
		if version, err := strconv.Atoi(spec.Version); err == nil {
			if supportedVersion, err := strconv.Atoi(domain.SupportedApplicationDescriptorVersion); err == nil {
				if version < supportedVersion {
					if spec.LibraryVersion != "" {
						util.LogAndExit("Image %s, built with sbt-cloudflow version '%s', is incompatible and no longer supported. Please upgrade sbt-cloudflow and rebuild the image.", dockerImagePath, spec.LibraryVersion)
					} else {
						util.LogAndExit("Image %s is incompatible and no longer supported. Please upgrade sbt-cloudflow and rebuild the image.", dockerImagePath)
					}
				}
				if version > supportedVersion {
					if spec.LibraryVersion != "" {
						util.LogAndExit("Image %s, built with sbt-cloudflow version '%s', is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again.", dockerImagePath, spec.LibraryVersion)
					} else {
						util.LogAndExit("Image %s is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again.", dockerImagePath)
					}
				}
			}
		}

		util.LogAndExit("Image %s is incompatible and no longer supported. Please update sbt-cloudflow and rebuild the image.", dockerImagePath)
	}

	digest := applicationDescriptorImageDigest.ImageDigest

	var imageRef string
	if dockerRegistryURL == "" {
		imageRef = dockerRepository + "/" + digest
	} else {
		imageRef = dockerRegistryURL + "/" + dockerRepository + "/" + digest
	}

	// replace tagged images with digest based names
	for i := range spec.Deployments {
		spec.Deployments[i].Image = imageRef
	}
	return spec, *pulledImage
}

func splitOnFirstCharacter(str string, char byte) ([]string, error) {
	var arr []string
	if idx := strings.IndexByte(str, char); idx >= 0 {
		arr = append(arr, str[:idx])
		arr = append(arr, strings.Trim(str[idx+1:], "\""))
		return arr, nil
	}
	return arr, fmt.Errorf("The configuration parameters must be formated as space delimited '[streamlet-name[.[property]=[value]' pairs")
}

// SplitConfigurationParameters maps string representations of a key/value pair into a map
func SplitConfigurationParameters(configurationParameters []string) map[string]string {
	configurationKeyValues := make(map[string]string)

	for _, v := range configurationParameters {
		keyValueArray, err := splitOnFirstCharacter(v, '=')
		if err != nil {
			util.LogAndExit(err.Error())
		} else {
			configurationKeyValues[keyValueArray[0]] = keyValueArray[1]

		}
	}
	return configurationKeyValues
}

func getConfigForStreamlet(conf *configuration.Config, streamletName string) *configuration.Config {
	return conf.GetConfig(cloudflowStreamletsPrefix + streamletName)
}

// ValidateVolumeMounts validates that volume mounts command line arguments corresponds to a volume mount descriptor in the AD and that the PVC named in the argument exists
func ValidateVolumeMounts(k8sClient *kubernetes.Clientset, spec domain.CloudflowApplicationSpec, volumeMountPVCNameArray []string) (domain.CloudflowApplicationSpec, error) {
	// build a map of all user-specified volume mount arguments where the key
	// is [streamlet name].[volume mount key] and the value is the name of the
	// PVC to use for the mount.
	volumeMountPVCName := make(map[string]string)
	for _, e := range volumeMountPVCNameArray {
		keyValue := strings.Split(e, "=")
		if len(keyValue) != 2 {
			return spec, fmt.Errorf("\nInvalid volume mount parameter found.\n`%s` is not correctly formatted (`key=value`)", e)
		}
		volumeMountPVCName[keyValue[0]] = keyValue[1]
	}

	for _, streamlet := range spec.Streamlets {
		for m, mount := range streamlet.Descriptor.VolumeMounts {
			volumeMountName := prefixWithStreamletName(streamlet.Name, mount.Name)

			pvcName, ok := volumeMountPVCName[volumeMountName]
			if ok == false {
				return spec, fmt.Errorf("The following volume mount needs to be bound to a Persistence Volume claim using the --volume-mount flag\n\n- %s", volumeMountName)
			}

			pvc, err := k8sClient.CoreV1().PersistentVolumeClaims(spec.AppID).Get(pvcName, metav1.GetOptions{})
			if err != nil {
				return spec, fmt.Errorf("Persistent Volume Claim `%s` cannot be found in namespace `%s`", pvcName, spec.AppID)
			}

			if accessModeExists(pvc.Spec.AccessModes, mount.AccessMode) == false {
				return spec, fmt.Errorf("Persistent Volume Claim `%s` does not support requested access mode '%s'.\nThe PVC instead provides the following access modes: %v", pvcName, mount.AccessMode, pvc.Spec.AccessModes)
			}

			// We need to set the PVC on the associated streamlet deployment
			deploymentName := spec.AppID + "." + streamlet.Name
			for _, deployment := range spec.Deployments {
				if deployment.Name == deploymentName {
					for dm, deploymentMount := range deployment.VolumeMounts {
						if deploymentMount.Name == mount.Name {
							// Set the user-specified PVC name on the matching deployment in the spec (mutates the spec)
							deployment.VolumeMounts[dm].PVCName = pvcName
							// We also have to set it on the descriptor for backwards compatibility
							// with the operator, which breaks on CRs if we don't do this.
							streamlet.Descriptor.VolumeMounts[m].PVCName = pvcName

							fmt.Printf("\nThe following volume mount is now bound to Persistent Volume Claim `%s`:\n\n- %s\n\n", pvcName, volumeMountName)
						}
					}
				}
			}
		}
	}

	return spec, nil
}

func accessModeExists(accessModes []corev1.PersistentVolumeAccessMode, accessModeToFind string) bool {

	for i := range accessModes {
		if string(accessModes[i]) == accessModeToFind {
			return true
		}
	}
	return false
}

// validateConfigurationAgainstDescriptor validates all configuration values against configuration parameter descriptors
func validateConfigurationAgainstDescriptor(spec domain.CloudflowApplicationSpec, streamletConfigs map[string]*configuration.Config) error {

	type ValidationErrorDescriptor struct {
		FqKey              string
		ProblemDescription string
	}

	var missingKeys []ValidationErrorDescriptor
	var invalidKeys []ValidationErrorDescriptor

	for _, streamlet := range spec.Streamlets {
		conf := streamletConfigs[streamlet.Name]
		if conf == nil {
			conf = configuration.ParseString("")
		}
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			streamletConfigKey := prefixWithStreamletName(streamlet.Name, descriptor.Key)
			fqKey := cloudflowStreamletsPrefix + streamletConfigKey

			if err := validateStreamletConfigValue(descriptor, conf.GetString(fqKey)); err != nil {
				invalidKeys = append(invalidKeys, ValidationErrorDescriptor{streamletConfigKey, err.Error()})
			}

			if conf.GetString(fqKey) == "" {
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

func validateStreamletConfigValue(descriptor domain.ConfigParameterDescriptor, value string) error {
	switch descriptor.Type {

	case "bool":
		if value != "true" && value != "false" &&
			value != "on" && value != "off" &&
			value != "yes" && value != "no" {
			return fmt.Errorf("Value `%s`is not a valid boolean. A boolean must be one of the following textual values `true','false',`yes`,`no`,`on` or `off`", value)
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
		if err := util.ValidateDuration(value); err != nil {
			return err
		}
	case "memorysize":
		if err := util.ValidateMemorySize(value); err != nil {
			return err
		}
	default:
		return fmt.Errorf("encountered an unknown validation type `%s`. Please make sure that the CLI is up-to-date", descriptor.Type)
	}

	return nil
}

// HandleConfig handles configuration files and configuration arguments
func HandleConfig(
	k8sClient *kubernetes.Clientset,
	namespace string,
	applicationSpec domain.CloudflowApplicationSpec,
	configurationArguments map[string]string,
	configFiles []string) {

	existingConfigs := getConfigFromSecrets(k8sClient, applicationSpec)
	streamletNameSecretMap, err := handleConfig(namespace, applicationSpec, configurationArguments, configFiles, existingConfigs)
	if err != nil {
		util.LogErrorAndExit(err)
	}
	createStreamletSecrets(k8sClient, namespace, streamletNameSecretMap)
}

func handleConfig(
	namespace string,
	applicationSpec domain.CloudflowApplicationSpec,
	configurationArguments map[string]string,
	configFiles []string,
	existingConfigs map[string]*configuration.Config) (map[string]*corev1.Secret, error) {

	configMergedFromFiles, err := loadAndMergeConfigs(configFiles)
	if err != nil {
		return nil, err
	}

	configMergedFromFiles = addDefaultValues(applicationSpec, configMergedFromFiles)

	streamletConfigs := addExistingStreamletConfigsIfNotPresentInFile(applicationSpec, configMergedFromFiles, existingConfigs)

	streamletConfigs = addApplicationLevelConfig(configMergedFromFiles, streamletConfigs)

	streamletConfigs = addArguments(applicationSpec, streamletConfigs, configurationArguments)

	validationError := validateConfigurationAgainstDescriptor(applicationSpec, streamletConfigs)
	if validationError != nil {
		return nil, validationError
	}
	//TODO need to create empty secrets for streamlets that have no args.
	streamletNameSecretMap, err := createSecretsData(&applicationSpec, streamletConfigs)
	return streamletNameSecretMap, err
}

// adds existing configs (loaded from existing secrets), only if there is no config for the streamlet in the merged files (otherwise you can't unset anything)
func addExistingStreamletConfigsIfNotPresentInFile(spec domain.CloudflowApplicationSpec, configMergedFromFiles *configuration.Config, existingConfigs map[string]*configuration.Config) map[string]*configuration.Config {
	configs := make(map[string]*configuration.Config)
	// add existing configs loaded from secrets, only if there is no config for the streamlet in the merged files (otherwise you can't unset anything)
	for streamletName, existingConfig := range existingConfigs {
		streamletConfigFromFile := configMergedFromFiles.GetConfig(cloudflowStreamletsPrefix + streamletName)
		if streamletConfigFromFile == nil {
			existingStreamletConfig := moveToRoot(existingConfig.GetConfig(cloudflowStreamletsPrefix+streamletName), streamletName)
			fmt.Printf("Existing configuration values will be used for '%s'\n", streamletName)
			configs[streamletName] = existingStreamletConfig
		}
	}
	// add streamlet configs found in the --conf files
	for _, streamlet := range spec.Streamlets {
		streamletConfigFromFile := moveToRoot(configMergedFromFiles.GetConfig(cloudflowStreamletsPrefix+streamlet.Name), streamlet.Name)
		if streamletConfigFromFile != nil {
			configs[streamlet.Name] = streamletConfigFromFile
		}
	}
	// move the configs under cloudflow.streamlets
	for streamletName, conf := range configs {
		configs[streamletName] = moveToRoot(moveToRoot(conf, "streamlets"), "cloudflow")
	}

	return configs
}

func addApplicationLevelConfig(configMergedFromFiles *configuration.Config, streamletConfigs map[string]*configuration.Config) map[string]*configuration.Config {
	// merge in application level settings from config files.
	for streamletName, streamletConfig := range streamletConfigs {

		// merging per key is quite expensive, but not trusting the other methods in the go-akka lib just yet..
		keys := configMergedFromFiles.Root().GetObject().GetKeys()
		for _, key := range keys {
			if key != "cloudflow" {
				appLevelConfigItem := moveToRoot(configMergedFromFiles.GetConfig(key), key)
				streamletConfigs[streamletName] = mergeWithFallback(streamletConfig, appLevelConfigItem)

			}
		}

		streamletLevelConf := streamletConfig.GetConfig(fmt.Sprintf("cloudflow.streamlets.%s.application-conf", streamletName))
		if streamletLevelConf != nil {
			streamletConfigs[streamletName] = mergeWithFallback(streamletLevelConf, streamletConfig)
		}
	}
	return streamletConfigs
}

// Workaround for bad merging, root CANNOT be a dot separated path, needs fix in go-hocon
func moveToRoot(config *configuration.Config, root string) *configuration.Config {
	if config == nil {
		return config
	}
	return configuration.NewConfigFromRoot(config.Root().AtKey(root))
}

//LoadAndMergeConfigs loads specified configuration files and merges them into one Config
func loadAndMergeConfigs(configFiles []string) (*configuration.Config, error) {
	// For some reason WithFallback does not work as expected, so we'll use this workaround for now.
	var sb strings.Builder

	for _, file := range configFiles {
		if !FileExists(file) {
			return nil, fmt.Errorf("configuration file %s passed with --conf does not exist", file)
		}
		content, err := ioutil.ReadFile(file)
		if err != nil {
			return nil, fmt.Errorf("could not read configuration file %s", file)
		}
		sb.Write(content)
		sb.WriteString("")

	}

	confStr := sb.String()
	// go-akka/configuration can panic on error (not experienced), need to fix that in a fork.
	config := configuration.ParseString(confStr)
	return config, nil
}

func addDefaultValues(applicationSpec domain.CloudflowApplicationSpec, config *configuration.Config) *configuration.Config {
	var sb strings.Builder
	for _, streamlet := range applicationSpec.Streamlets {
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			fqKey := prefixWithStreamletName(streamlet.Name, descriptor.Key)
			if config.GetString(cloudflowStreamletsPrefix+fqKey) == "" {
				if len(descriptor.DefaultValue) > 0 {
					fmt.Printf("Default value '%s' will be used for configuration parameter '%s'\n", descriptor.DefaultValue, fqKey)
					// Fix cloudflowStreamletsPrefix
					sb.WriteString(fmt.Sprintf("%s%s=\"%s\"\r\n", cloudflowStreamletsPrefix, fqKey, descriptor.DefaultValue))
				}
			}
		}
	}
	defaults := configuration.ParseString(sb.String())
	config = mergeWithFallback(defaults, config)
	return config
}

func addArguments(spec domain.CloudflowApplicationSpec, configs map[string]*configuration.Config, configurationArguments map[string]string) map[string]*configuration.Config {
	// add pass through args on application level
	written := make(map[string]string)

	for _, streamlet := range spec.Streamlets {
		var sb strings.Builder
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			key := prefixWithStreamletName(streamlet.Name, descriptor.Key)
			configValue := configurationArguments[key]
			if configValue != "" {
				sb.WriteString(fmt.Sprintf("%s%s=\"%s\"\r\n", cloudflowStreamletsPrefix, key, configValue))
				written[key] = configValue
			}
		}
		if sb.Len() > 0 {
			if configs[streamlet.Name] != nil {
				configs[streamlet.Name] = mergeWithFallback(configuration.ParseString(sb.String()), configs[streamlet.Name])
			} else {
				configs[streamlet.Name] = configuration.ParseString(sb.String())
			}
		}
	}

	var sbAppLevel strings.Builder
	for key, configValue := range configurationArguments {
		if _, ok := written[key]; !ok {
			sbAppLevel.WriteString(fmt.Sprintf("%s=\"%s\"\r\n", key, configValue))
		}
	}
	if sbAppLevel.Len() > 0 {
		appLevelConfFromArgs := configuration.ParseString(sbAppLevel.String())

		for streamletName, config := range configs {
			configs[streamletName] = mergeWithFallback(appLevelConfFromArgs, config)
		}
	}
	return configs
}

// workaround for WithFallback not working in go-akka/configuration
func mergeWithFallback(config *configuration.Config, fallback *configuration.Config) *configuration.Config {
	if config == nil {
		config = configuration.ParseString("")
	}
	if fallback == nil {
		fallback = configuration.ParseString("")
	}
	confStr := config.String()
	fallbackStr := fallback.String()
	var sb strings.Builder
	// For some reason merging does not work on two objects in { } (it just reads the first and stops reading)
	sb.WriteString("a : ")
	sb.WriteString(fallbackStr)
	sb.WriteString("\n\n")
	sb.WriteString("a : ")
	sb.WriteString(confStr)
	sb.WriteString("\n")
	conf := configuration.ParseString(sb.String()).GetConfig("a")
	return conf
}

func createStreamletSecrets(k8sClient *kubernetes.Clientset, namespace string, streamletNameSecretMap map[string]*corev1.Secret) {
	for streamletName, secret := range streamletNameSecretMap {
		if _, err := k8sClient.CoreV1().Secrets(secret.ObjectMeta.Namespace).Get(secret.ObjectMeta.Name, metav1.GetOptions{}); err != nil {
			if _, err := k8sClient.CoreV1().Secrets(namespace).Create(secret); err != nil {
				util.LogAndExit("Failed to create secret %s, %s", streamletName, err.Error())
			}
		} else {
			if _, err := k8sClient.CoreV1().Secrets(namespace).Update(secret); err != nil {
				util.LogAndExit("Failed to create secret %s, %s", streamletName, err.Error())
			}
		}
	}
}

func getConfigFromSecrets(client *kubernetes.Clientset, spec domain.CloudflowApplicationSpec) map[string]*configuration.Config {
	var configs = make(map[string]*configuration.Config)

	for _, deployment := range spec.Deployments {
		if secret, err := client.CoreV1().Secrets(spec.AppID).Get(deployment.SecretName, metav1.GetOptions{}); err == nil {
			for _, configBytes := range secret.Data {
				conf := configuration.ParseString(string(configBytes))
				configs[deployment.StreamletName] = conf
			}
		}
	}
	return configs
}

func createSecretsData(spec *domain.CloudflowApplicationSpec, streamletConfigs map[string]*configuration.Config) (map[string]*corev1.Secret, error) {
	streamletSecretNameMap := make(map[string]*corev1.Secret)
	for _, streamlet := range spec.Streamlets {
		streamletName := streamlet.Name
		secretName := findSecretName(spec, streamletName)
		config := streamletConfigs[streamletName]
		if config != nil {
			secretMap := make(map[string]string)
			var sb strings.Builder
			sb.WriteString(config.String())
			sb.WriteString("\n")
			secretMap["secret.conf"] = sb.String()
			streamletSecretNameMap[secretName] = createSecret(spec.AppID, secretName, secretMap)
		} else {
			streamletSecretNameMap[secretName] = createSecret(spec.AppID, secretName, make(map[string]string))
		}
	}
	return streamletSecretNameMap, nil
}

func findSecretName(spec *domain.CloudflowApplicationSpec, streamletName string) string {
	for _, deployment := range spec.Deployments {
		if deployment.StreamletName == streamletName {
			return deployment.SecretName
		}
	}
	panic(fmt.Errorf("Could not find secret name for streamlet %s", streamletName))
}

func createSecret(appID string, name string, data map[string]string) *corev1.Secret {
	labels := domain.CreateLabels(appID)
	labels["com.lightbend.cloudflow/streamlet-name"] = name
	secret := &corev1.Secret{
		Type: corev1.SecretTypeOpaque,
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: appID,
			Labels:    labels,
		},
	}
	secret.StringData = data
	return secret
}

func prefixWithStreamletName(streamletName string, key string) string {
	return fmt.Sprintf("%s.%s", streamletName, key)
}

func formatStreamletConfigKeyFq(streamletName string, key string) string {
	return fmt.Sprintf("cloudflow.streamlets.%s", prefixWithStreamletName(streamletName, key))
}

// FileExists checks if a file exists and is not a directory.
func FileExists(filename string) bool {
	info, err := os.Stat(filename)
	if os.IsNotExist(err) {
		return false
	}
	return !info.IsDir()
}
