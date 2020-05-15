package config

import (
	"errors"
	"fmt"
	"io/ioutil"
	"regexp"
	"strconv"
	"strings"
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

const cloudflowStreamletsPrefix = "cloudflow.streamlets"
const cloudflowRuntimesPrefix = "cloudflow.runtimes"
const configParametersKey = "config-parameters"
const configKey = "config"
const kubernetesKey = "kubernetes"
const runtimeKey = "runtime"
const runtimesKey = "runtimes"
const streamletsKey = "streamlets"
const cloudflowKey = "cloudflow"

// HandleConfig handles configuration files and configuration arguments
func HandleConfig(
	args []string,
	k8sClient *kubernetes.Clientset,
	namespace string,
	applicationSpec cfapp.CloudflowApplicationSpec,
	configFiles []string) map[string]*corev1.Secret {
	configurationArguments, err := splitConfigurationParameters(args[1:])

	if err != nil {
		printutil.LogErrorAndExit(err)
	}

	streamletNameSecretMap, err := handleConfig(namespace, applicationSpec, configurationArguments, configFiles)

	if err != nil {
		printutil.LogErrorAndExit(err)
	}
	return streamletNameSecretMap
}

func handleConfig(
	namespace string,
	applicationSpec cfapp.CloudflowApplicationSpec,
	configurationArguments map[string]string,
	configFiles []string) (map[string]*corev1.Secret, error) {

	config, err := loadAndMergeConfigs(configFiles)
	if err != nil {
		return nil, err
	}

	config = addDefaultValuesFromSpec(applicationSpec, config)
	config = addCommandLineArguments(applicationSpec, config, configurationArguments)

	if err := validateConfig(config, applicationSpec); err != nil {
		return nil, err
	}

	streamletConfigs := createStreamletConfigsMap(applicationSpec, config)

	validationError := validateConfigurationAgainstDescriptor(applicationSpec, streamletConfigs)
	if validationError != nil {
		return nil, validationError
	}
	streamletNameSecretMap, err := createInputSecretsMap(&applicationSpec, streamletConfigs)
	return streamletNameSecretMap, err
}

func createStreamletConfigsMap(spec cfapp.CloudflowApplicationSpec, config *configuration.Config) map[string]*configuration.Config {
	configs := make(map[string]*configuration.Config)

	for _, streamlet := range spec.Streamlets {
		streamletConfig := config.GetConfig(streamletConfigKey(streamlet.Name))
		if streamletConfig == nil {
			streamletConfig = EmptyConfig()
		} else {
			streamletConfig = moveToRootPath(streamletConfig, streamletConfigKey(streamlet.Name))
		}
		rKey := runtimeConfigKey(streamlet.Descriptor.Runtime)
		runtimeConfig := config.GetConfig(rKey)
		if runtimeConfig != nil {
			streamletConfig = mergeWithFallback(streamletConfig, moveToRootPath(runtimeConfig, streamletRuntimeConfigKey(streamlet.Name)))
		}
		if streamletConfig != nil {
			configs[streamlet.Name] = streamletConfig
		}
	}
	return configs
}

//LoadAndMergeConfigs loads specified configuration files and merges them into one Config
func loadAndMergeConfigs(configFiles []string) (*configuration.Config, error) {
	// For some reason WithFallback does not work as expected, so we'll use this workaround for now.
	var sb strings.Builder

	for _, file := range configFiles {
		if !fileutil.FileExists(file) {
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

	if config.GetConfig(cloudflowStreamletsPrefix) == nil && config.GetConfig(cloudflowRuntimesPrefix) == nil {
		return nil, fmt.Errorf("configuration does not contain '%s' or '%s' config sections", cloudflowStreamletsPrefix, cloudflowRuntimesPrefix)
	}
	streamletsConfig := config.GetConfig(cloudflowStreamletsPrefix)
	if streamletsConfig != nil && streamletsConfig.Root().IsObject() {
		for streamletName := range streamletsConfig.Root().GetObject().Items() {
			streamletConfig := streamletsConfig.GetConfig(streamletName)
			if streamletConfig != nil && streamletConfig.Root().IsObject() {
				if streamletConfig.GetConfig(configParametersKey) == nil &&
					streamletConfig.GetConfig(configKey) == nil &&
					streamletConfig.GetConfig(kubernetesKey) == nil {
					return nil, fmt.Errorf("streamlet config %s.%s does not contain '%s', '%s' or '%s' config sections", cloudflowStreamletsPrefix, streamletName, configParametersKey, configKey, kubernetesKey)
				}
				for streamletConfigSectionKey := range streamletConfig.Root().GetObject().Items() {
					if !(streamletConfigSectionKey == configParametersKey || streamletConfigSectionKey == configKey || streamletConfigSectionKey == kubernetesKey) {
						return nil, fmt.Errorf("streamlet config %s.%s contains unknown section '%s'", cloudflowStreamletsPrefix, streamletName, streamletConfigSectionKey)
					}
				}
			}
		}
	}
	return config, nil
}

func validateConfig(config *configuration.Config, applicationSpec cfapp.CloudflowApplicationSpec) error {

	// TODO kubernetes section: valide args to known path formats:
	// cloudflow.streamlets.<streamlet>.kubernetes.<k8s-keys>
	// cloudflow.runtimes.<streamlet>.kubernetes.<k8s-keys>

	streamletsConfig := config.GetConfig(cloudflowStreamletsPrefix)
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
				return fmt.Errorf("Configuration contains unknown streamlet '%s'", streamletName)
			}

			streamletConfig := streamletsConfig.GetConfig(streamletName)
			if streamletConfig != nil && streamletConfig.Root().IsObject() {
				if streamletConfig.GetConfig(configParametersKey) == nil &&
					streamletConfig.GetConfig(configKey) == nil &&
					streamletConfig.GetConfig(kubernetesKey) == nil {
					return fmt.Errorf("streamlet config in path '%s.%s' does not contain '%s', '%s' or '%s' config sections", cloudflowStreamletsPrefix, streamletName, configParametersKey, configKey, kubernetesKey)
				}
				for streamletConfigSectionKey := range streamletConfig.Root().GetObject().Items() {
					if !(streamletConfigSectionKey == configParametersKey || streamletConfigSectionKey == configKey || streamletConfigSectionKey == kubernetesKey) {
						return fmt.Errorf("streamlet config in path '%s.%s' contains unknown section '%s'", cloudflowStreamletsPrefix, streamletName, streamletConfigSectionKey)
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
									return fmt.Errorf("Unknown config parameter '%s' found for streamlet '%s' in path '%s.%s.%s'", configParKey, streamletName, cloudflowStreamletsPrefix, streamletName, configParametersKey)
								}
							}
						}
					}
				}
			}
		}
	}
	runtimesConfig := config.GetConfig(cloudflowRuntimesPrefix)
	if runtimesConfig != nil && runtimesConfig.Root().IsObject() {
		for runtime := range runtimesConfig.Root().GetObject().Items() {
			runtimeConfig := runtimesConfig.GetConfig(runtime)
			if runtimeConfig != nil && runtimeConfig.Root().IsObject() {
				if runtimeConfig.GetConfig(configKey) == nil &&
					runtimeConfig.GetConfig(kubernetesKey) == nil {
					return fmt.Errorf("runtime config %s.%s does not contain '%s' or '%s' config sections", cloudflowRuntimesPrefix, runtime, configKey, kubernetesKey)
				}
				for runtimeConfigKey := range runtimeConfig.Root().GetObject().Items() {
					if !(runtimeConfigKey == configKey || runtimeConfigKey == kubernetesKey) {
						return fmt.Errorf("streamlet config %s.%s contains unknown section '%s'", cloudflowRuntimesPrefix, runtime, runtimeConfigKey)
					}
				}
			}
		}
	}
	if streamletsConfig == nil && runtimesConfig == nil {
		return fmt.Errorf("Missing %s or %s config sections", cloudflowStreamletsPrefix, cloudflowRuntimesPrefix)
	}
	return nil
}

func addDefaultValuesFromSpec(applicationSpec cfapp.CloudflowApplicationSpec, config *configuration.Config) *configuration.Config {
	var sb strings.Builder
	for _, streamlet := range applicationSpec.Streamlets {
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			fqKey := prefixConfigParameterKey(streamlet.Name, descriptor.Key)
			if config.GetString(fqKey) == "" {
				if len(descriptor.DefaultValue) > 0 {
					fmt.Printf("Default value '%s' will be used for configuration parameter '%s'\n", descriptor.DefaultValue, fqKey)
					sb.WriteString(fmt.Sprintf("%s=\"%s\"\r\n", fqKey, descriptor.DefaultValue))
				}
			}
		}
	}
	defaults := configuration.ParseString(sb.String())
	config = mergeWithFallback(defaults, config)
	return config
}

func addCommandLineArguments(spec cfapp.CloudflowApplicationSpec, config *configuration.Config, configurationArguments map[string]string) *configuration.Config {
	written := make(map[string]string)
	var sb strings.Builder
	for _, streamlet := range spec.Streamlets {
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			key := prefixConfigParameterKey(streamlet.Name, descriptor.Key)
			configValue := configurationArguments[key]
			if configValue != "" {
				sb.WriteString(fmt.Sprintf("%s=\"%s\"\r\n", key, configValue))
				written[key] = configValue
			}
		}
	}
	if sb.Len() > 0 {
		config = mergeWithFallback(configuration.ParseString(sb.String()), config)
	}

	var sbNonValidatedParameters strings.Builder
	for key, configValue := range configurationArguments {
		if _, ok := written[key]; !ok {
			sbNonValidatedParameters.WriteString(fmt.Sprintf("%s=\"%s\"\r\n", key, configValue))
		}
	}
	if sbNonValidatedParameters.Len() > 0 {
		nonValidatedConfFromArgs := configuration.ParseString(sbNonValidatedParameters.String())
		config = mergeWithFallback(nonValidatedConfFromArgs, config)
	}
	return config
}

func createInputSecretsMap(spec *cfapp.CloudflowApplicationSpec, streamletConfigs map[string]*configuration.Config) (map[string]*corev1.Secret, error) {
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
			streamletSecretNameMap[secretName] = createInputSecret(spec.AppID, secretName, secretMap)
		} else {
			secretMap := make(map[string]string)
			secretMap["secret.conf"] = "{}"
			streamletSecretNameMap[secretName] = createInputSecret(spec.AppID, secretName, secretMap)
		}
	}
	return streamletSecretNameMap, nil
}

// UpdateSecretsWithOwnerReference updates the secret with the ownerreference passed in
func UpdateSecretsWithOwnerReference(cloudflowCROwnerReference metav1.OwnerReference, secrets map[string]*corev1.Secret) map[string]*corev1.Secret {
	for key, value := range secrets {
		value.ObjectMeta.OwnerReferences = []metav1.OwnerReference{cloudflowCROwnerReference}
		secrets[key] = value
	}
	return secrets
}

func findSecretName(spec *cfapp.CloudflowApplicationSpec, streamletName string) string {
	for _, deployment := range spec.Deployments {
		if deployment.StreamletName == streamletName {
			return deployment.SecretName
		}
	}
	panic(fmt.Errorf("could not find secret name for streamlet %s", streamletName))
}

func createInputSecret(appID string, name string, data map[string]string) *corev1.Secret {
	labels := cfapp.CreateLabels(appID)
	labels["com.lightbend.cloudflow/streamlet-name"] = name
	// indicates the secret contains cloudflow config format
	labels["com.lightbend.cloudflow/config-format"] = "input"
	secret := &corev1.Secret{
		Type: corev1.SecretTypeOpaque,
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-%s", "config", name),
			Namespace: appID,
			Labels:    labels,
		},
	}
	secret.StringData = data
	return secret
}

func runtimeConfigKey(runtime string) string {
	return fmt.Sprintf("%s.%s.%s", cloudflowRuntimesPrefix, runtime, configKey)
}

func streamletConfigKey(streamletName string) string {
	return fmt.Sprintf("%s.%s", cloudflowStreamletsPrefix, streamletName)
}

func streamletRuntimeConfigKey(streamletName string) string {
	return fmt.Sprintf("%s.%s.%s", cloudflowStreamletsPrefix, streamletName, configKey)
}

func configParametersPrefix(streamletName string) string {
	return fmt.Sprintf("%s.%s.%s", cloudflowStreamletsPrefix, streamletName, configParametersKey)
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
func validateConfigurationAgainstDescriptor(spec cfapp.CloudflowApplicationSpec, streamletConfigs map[string]*configuration.Config) error {

	type ValidationErrorDescriptor struct {
		FqKey              string
		ProblemDescription string
	}

	var missingKeys []ValidationErrorDescriptor
	var invalidKeys []ValidationErrorDescriptor

	for _, streamlet := range spec.Streamlets {
		conf := streamletConfigs[streamlet.Name]
		if conf == nil {
			conf = EmptyConfig()
		}
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			streamletConfigKey := prefixConfigParameterKey(streamlet.Name, descriptor.Key)
			fqKey := streamletConfigKey

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

// workaround for WithFallback not working in go-akka/configuration
func mergeWithFallback(config *configuration.Config, fallback *configuration.Config) *configuration.Config {
	if config == nil {
		config = EmptyConfig()
	}
	if fallback == nil {
		fallback = EmptyConfig()
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

func moveToRootPath(config *configuration.Config, dotSeparatedPath string) *configuration.Config {
	parts := strings.Split(dotSeparatedPath, ".")
	if len(parts) > 0 {
		for i := len(parts) - 1; i >= 0; i-- {
			config = moveToRoot(config, parts[i])
		}
	}
	return config
}

// Workaround for bad merging, root CANNOT be a dot separated path, needs fix in go-hocon
func moveToRoot(config *configuration.Config, root string) *configuration.Config {
	if config == nil {
		return config
	}
	return configuration.NewConfigFromRoot(config.Root().AtKey(root))
}

//EmptyConfig creates an empty config (workaround, not found in go-akka configuration)
func EmptyConfig() *configuration.Config {
	return configuration.ParseString("")
}
