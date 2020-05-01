package deploy

import (
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/docker"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"k8s.io/client-go/kubernetes"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

//CheckApplicationDescriptorVersion checks the version, logs and exits oif not supported
func CheckApplicationDescriptorVersion(spec domain.CloudflowApplicationSpec) {
	if spec.Version != domain.SupportedApplicationDescriptorVersion {
		// If the version is an int, compare them, otherwise provide a more general message.
		if version, err := strconv.Atoi(spec.Version); err == nil {
			if supportedVersion, err := strconv.Atoi(domain.SupportedApplicationDescriptorVersion); err == nil {
				if version < supportedVersion {
					if spec.LibraryVersion != "" {
						util.LogAndExit("Application built with sbt-cloudflow version '%s', is incompatible and no longer supported. Please upgrade sbt-cloudflow and rebuild the application with 'sbt buildApp'.", spec.LibraryVersion)
					} else {
						util.LogAndExit("Application is incompatible and no longer supported. Please upgrade sbt-cloudflow and rebuild the application with 'sbt buildApp'.")
					}
				}
				if version > supportedVersion {
					if spec.LibraryVersion != "" {
						util.LogAndExit("Application built with sbt-cloudflow version '%s', is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again.", spec.LibraryVersion)
					} else {
						util.LogAndExit("Application is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again.")
					}
				}
			}
		}

		util.LogAndExit("Application is incompatible and no longer supported. Please update sbt-cloudflow and rebuild the application with 'sbt buildApp'.")
	}
}

// UpdateImageRefsWithDigests updates the imagesRefs to include the digest of the pulled images
func UpdateImageRefsWithDigests(spec domain.CloudflowApplicationSpec, pulledImages map[string]*docker.PulledImage, dockerRegistryURL string, dockerRepository string) domain.CloudflowApplicationSpec {

	// replace tagged images with digest based names
	for i := range spec.Deployments {
		digest := pulledImages[spec.Deployments[i].Name].Digest

		var imageRef string
		if dockerRegistryURL == "" {
			imageRef = dockerRepository + "/" + digest
		} else {
			imageRef = dockerRegistryURL + "/" + dockerRepository + "/" + digest
		}

		spec.Deployments[i].Image = imageRef
	}
	return spec
}

func splitOnFirstCharacter(str string, char byte) ([]string, error) {
	var arr []string
	if idx := strings.IndexByte(str, char); idx >= 0 {
		arr = append(arr, str[:idx])
		arr = append(arr, strings.Trim(str[idx+1:], "\""))
		return arr, nil
	}
	return arr, fmt.Errorf("the configuration parameters must be formated as space delimited '[streamlet-name[.[property]=[value]' pairs")
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

// AppendExistingValuesNotConfigured adds values for those keys that are not entered by the user, which do already exist in secrets
func AppendExistingValuesNotConfigured(client *kubernetes.Clientset, spec domain.CloudflowApplicationSpec, configurationKeyValues map[string]string) map[string]string {
	existingConfigurationKeyValues := make(map[string]string)
	for _, deployment := range spec.Deployments {
		if secret, err := client.CoreV1().Secrets(spec.AppID).Get(deployment.SecretName, metav1.GetOptions{}); err == nil {
			for _, configValue := range secret.Data {
				lines := strings.Split(string(configValue), "\r\n")
				for _, line := range lines {
					cleaned := strings.TrimPrefix(strings.TrimSpace(line), "cloudflow.streamlets.")
					if len(cleaned) != 0 {
						keyValueArray, err := splitOnFirstCharacter(cleaned, '=')
						if err != nil {
							util.LogAndExit("Configuration for streamlet %s in secret %s is corrupted. %s", deployment.StreamletName, secret.Name, err.Error())
						} else {
							existingConfigurationKeyValues[keyValueArray[0]] = keyValueArray[1]
						}
					}
				}
			}
		}
	}

	for _, streamlet := range spec.Streamlets {
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			fqKey := prefixWithStreamletName(streamlet.Name, descriptor.Key)
			if _, ok := configurationKeyValues[fqKey]; !ok {
				if _, ok := existingConfigurationKeyValues[fqKey]; ok {
					fmt.Printf("Existing value will be used for configuration parameter '%s'\n", fqKey)
					configurationKeyValues[fqKey] = existingConfigurationKeyValues[fqKey]
				}
			}
		}
	}
	return configurationKeyValues
}

// AppendDefaultValuesForMissingConfigurationValues adds default values for those keys that are not entered by the user
func AppendDefaultValuesForMissingConfigurationValues(spec domain.CloudflowApplicationSpec, configurationKeyValues map[string]string) map[string]string {
	for _, streamlet := range spec.Streamlets {
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			fqKey := prefixWithStreamletName(streamlet.Name, descriptor.Key)
			if _, ok := configurationKeyValues[fqKey]; !ok {
				if len(descriptor.DefaultValue) > 0 {
					fmt.Printf("Default value '%s' will be used for configuration parameter '%s'\n", descriptor.DefaultValue, fqKey)
					configurationKeyValues[fqKey] = descriptor.DefaultValue
				}
			}
		}
	}
	return configurationKeyValues
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
				return spec, fmt.Errorf("persistent Volume Claim `%s` cannot be found in namespace `%s`", pvcName, spec.AppID)
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

// ValidateConfigurationAgainstDescriptor validates all configuration parameter keys in the descriptor against a set of provided keys
func ValidateConfigurationAgainstDescriptor(spec domain.CloudflowApplicationSpec, configurationKeyValues map[string]string) (map[string]string, error) {

	type ValidationErrorDescriptor struct {
		FqKey              string
		ProblemDescription string
	}

	var missingKeys []ValidationErrorDescriptor
	var invalidKeys []ValidationErrorDescriptor

	for _, streamlet := range spec.Streamlets {
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			fqKey := prefixWithStreamletName(streamlet.Name, descriptor.Key)

			if err := validateStreamletConfigKey(descriptor, configurationKeyValues[fqKey]); err != nil {
				invalidKeys = append(invalidKeys, ValidationErrorDescriptor{fqKey, err.Error()})
			}

			if _, ok := configurationKeyValues[fqKey]; !ok {
				missingKeys = append(missingKeys, ValidationErrorDescriptor{fqKey, descriptor.Description})
			}
		}
	}

	var str strings.Builder
	if len(missingKeys) > 0 {
		str.WriteString("Please provide values for the following configuration parameter(s):\n")
		for i := range missingKeys {
			str.WriteString(fmt.Sprintf("- %s - %s\n", missingKeys[i].FqKey, missingKeys[i].ProblemDescription))
		}
		return make(map[string]string), errors.New(str.String())
	}
	if len(invalidKeys) > 0 {
		str.WriteString("The following configuration parameter(s) have failed to validate:\n")
		for i := range invalidKeys {
			str.WriteString(fmt.Sprintf("- %s - %s\n", invalidKeys[i].FqKey, invalidKeys[i].ProblemDescription))
		}
		return make(map[string]string), errors.New(str.String())
	}
	return configurationKeyValues, nil
}

func validateStreamletConfigKey(descriptor domain.ConfigParameterDescriptor, value string) error {
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

// CreateSecretsData creates a map of streamlet names and K8s Secrets for those streamlets with configuration parameters,
// the secrets contain a single key/value where the key is the name of the hocon configuration file
func CreateSecretsData(spec *domain.CloudflowApplicationSpec, configurationKeyValues map[string]string) map[string]*corev1.Secret {
	streamletSecretNameMap := make(map[string]*corev1.Secret)
	for _, streamlet := range spec.Streamlets {
		var str strings.Builder
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			fqKey := formatStreamletConfigKeyFq(streamlet.Name, descriptor.Key)
			str.WriteString(fmt.Sprintf("%s=\"%s\"\r\n", fqKey, configurationKeyValues[prefixWithStreamletName(streamlet.Name, descriptor.Key)]))
		}
		secretMap := make(map[string]string)
		secretMap["secret.conf"] = str.String()
		secretName := findSecretName(spec, streamlet.Name)
		streamletSecretNameMap[secretName] = createSecret(spec.AppID, secretName, secretMap)
	}
	return streamletSecretNameMap
}

// UpdateSecretsWithOwnerReference updates the secret with the ownerreference passed in
func UpdateSecretsWithOwnerReference(cloudflowCROwnerReference metav1.OwnerReference, secrets map[string]*corev1.Secret) map[string]*corev1.Secret {
	for key, value := range secrets {
		value.ObjectMeta.OwnerReferences = []metav1.OwnerReference{cloudflowCROwnerReference}
		secrets[key] = value
	}
	return secrets
}

func findSecretName(spec *domain.CloudflowApplicationSpec, streamletName string) string {
	for _, deployment := range spec.Deployments {
		if deployment.StreamletName == streamletName {
			return deployment.SecretName
		}
	}
	panic(fmt.Errorf("could not find secret name for streamlet %s", streamletName))
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
