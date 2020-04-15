package deploy

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"golang.org/x/text/transform"
	"golang.org/x/text/unicode/norm"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/docker"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/verify"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"k8s.io/client-go/kubernetes"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

// GetCloudflowApplicationDescriptorFromDockerImage pulls a image and extracts the Cloudflow Application descriptor from a docker label
func GetCloudflowApplicationDescriptorFromDockerImage(dockerRegistryURL string, dockerRepository string, dockerImagePath string) (cloudflowapplication.CloudflowApplicationSpec, docker.PulledImage, error) {

	client, err := docker.GetVersionedClient()
	if err != nil {
		return cloudflowapplication.CloudflowApplicationSpec{}, docker.PulledImage{}, err
	}

	pulledImage, pullError := docker.PullImage(client, dockerImagePath)
	if pullError != nil {
		return cloudflowapplication.CloudflowApplicationSpec{}, docker.PulledImage{}, fmt.Errorf("Failed to pull image %s: %s", dockerImagePath, pullError.Error())
	}

	applicationDescriptorImageDigest := docker.GetCloudflowApplicationDescriptor(client, dockerImagePath)

	var spec cloudflowapplication.CloudflowApplicationSpec
	marshalError := json.Unmarshal([]byte(applicationDescriptorImageDigest.AppDescriptor), &spec)
	if marshalError != nil {
		return cloudflowapplication.CloudflowApplicationSpec{}, docker.PulledImage{}, fmt.Errorf("An unexpected error has occurred, please contact support and include the following information\n %s", marshalError.Error())
	}

	if spec.Version != cloudflowapplication.SupportedApplicationDescriptorVersion {
		// If the version is an int, compare them, otherwise provide a more general message.
		if version, err := strconv.Atoi(spec.Version); err == nil {
			if supportedVersion, err := strconv.Atoi(cloudflowapplication.SupportedApplicationDescriptorVersion); err == nil {
				if version < supportedVersion {
					if spec.LibraryVersion != "" {
						return cloudflowapplication.CloudflowApplicationSpec{}, docker.PulledImage{},
							fmt.Errorf("Image %s, built with sbt-cloudflow version '%s', is incompatible and no longer supported. Please upgrade sbt-cloudflow and rebuild the image", dockerImagePath, spec.LibraryVersion)
					}
					return cloudflowapplication.CloudflowApplicationSpec{}, docker.PulledImage{},
						fmt.Errorf("Image %s is incompatible and no longer supported. Please upgrade sbt-cloudflow and rebuild the image", dockerImagePath)
				}
				if version > supportedVersion {
					if spec.LibraryVersion != "" {
						return cloudflowapplication.CloudflowApplicationSpec{}, docker.PulledImage{},
							fmt.Errorf("Image %s, built with sbt-cloudflow version '%s', is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again", dockerImagePath, spec.LibraryVersion)
					}
					return cloudflowapplication.CloudflowApplicationSpec{}, docker.PulledImage{},
						fmt.Errorf("Image %s is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again", dockerImagePath)
				}
			}
		}

		return cloudflowapplication.CloudflowApplicationSpec{}, docker.PulledImage{},
			fmt.Errorf("Image %s is incompatible and no longer supported. Please update sbt-cloudflow and rebuild the image", dockerImagePath)
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
	return spec, *pulledImage, nil
}

func contains(s []cloudflowapplication.Streamlet, e string) bool {
	for _, a := range s {
		if a.Name == e {
			return true
		}
	}
	return false
}

// CreateApplicationSpecFromBlueprintAndImages pulls all images necessary to create a Cloudflow Application descriptor
// from a docker label. The function returns the created application spec and the pulled in images
func CreateApplicationSpecFromBlueprintAndImages(blueprint verify.Blueprint, pulledImages []*docker.PulledImage,
	imageDigests []string, replicas map[string]int) (cloudflowapplication.CloudflowApplicationSpec, error) {

	// get all streamlet descriptors, image digests and pulled images in arrays
	streamletDescriptors, deployImages, err := getStreamletDescriptorsAndImageInformation(blueprint, pulledImages, imageDigests)
	if err != nil {
		return cloudflowapplication.CloudflowApplicationSpec{}, err
	}

	// Spec.Streamlets & Spec.Deployments
	streamlets, deployments, err := getStreamletsAndDeployments(streamletDescriptors, deployImages, replicas, blueprint)
	if err != nil {
		return cloudflowapplication.CloudflowApplicationSpec{}, err
	}

	// check if the streamlets referred to in --scale are also present in the blueprint
	var notFound []string
	for s := range replicas {
    if !contains(streamlets, s) {
			notFound = append(notFound, s)
		}
	}

	if len(notFound) > 0 {
		return cloudflowapplication.CloudflowApplicationSpec{}, fmt.Errorf("Streamlet name(s) [%s] specified in --scale cannot be found in list of streamlets in the blueprint", strings.Join(notFound, ","))
	}

	// Spec.Connections
	conns := blueprint.GetConnections()

	// create the application spec
	spec := makeApplicationSpec(blueprint, cloudflowapplication.SupportedApplicationDescriptorVersion, conns, streamlets, deployments)

	return spec, nil
}

// makeApplicationSpec creates a CloudflowApplicationSpec out of all components
func makeApplicationSpec(bp verify.Blueprint, apiVersion string, conns []cloudflowapplication.Connection, streamlets []cloudflowapplication.Streamlet,
	deployments []cloudflowapplication.Deployment) cloudflowapplication.CloudflowApplicationSpec {

	var spec cloudflowapplication.CloudflowApplicationSpec
	spec.AgentPaths = make(map[string]string)
	spec.AppID = bp.GetName()
	spec.Version = apiVersion
	spec.LibraryVersion = cloudflowapplication.LibraryVersion
	spec.Connections = conns
	spec.Streamlets = streamlets
	spec.Deployments = deployments
	return spec
}

// getStreamletsAndDeployments gets the list of streamlets and deployments out of the blueprint and
// streamlet descriptors
func getStreamletsAndDeployments(streamletDescriptors []verify.StreamletDescriptor, deployImages map[string]string,
	replicas map[string]int, bp verify.Blueprint) ([]cloudflowapplication.Streamlet, []cloudflowapplication.Deployment, error) {

	var streamlets []cloudflowapplication.Streamlet
	var deployments []cloudflowapplication.Deployment
	for _, streamletDescriptor := range streamletDescriptors {
		streamletIDs := bp.GetStreamletIDsFromClassName(streamletDescriptor.ClassName)
		if len(streamletIDs) == 0 {
			continue
		}

		for _, streamletID := range streamletIDs {
			streamlet := cloudflowapplication.Streamlet{Descriptor: cloudflowapplication.Descriptor(streamletDescriptor), Name: streamletID}
			streamlets = append(streamlets, streamlet)
			deployment, err := makeDeployment(bp, streamletID, streamlet, deployImages, replicas)
			if err != nil {
				return nil, nil, err
			}
			deployments = append(deployments, deployment)
		}
	}
	return streamlets, deployments, nil
}

// getStreamletDescriptorsAndImageInformation fetches a bunch ofinformation needed to create an application spec
func getStreamletDescriptorsAndImageInformation(blueprint verify.Blueprint, pulledImages []*docker.PulledImage, imageDigests []string) ([]verify.StreamletDescriptor, map[string]string, error) {

	streamletDescriptors := blueprint.GetAllStreamletDescriptors()
	deployImages, err := getImageInfoNeededForDeployment(pulledImages, imageDigests)
	if err != nil {
		return nil, nil, err
	}

	return streamletDescriptors, deployImages, nil
}

func getImageInfoNeededForDeployment(pulledImages []*docker.PulledImage, imageDigests []string) (map[string]string, error) {
	var deployImages = make(map[string]string)

	for i, pulledImage := range pulledImages {
		// this format has to be used in Deployment: full-uri@sha
		// deployImage := fmt.Sprintf("%s@%s", strings.Split(pulledImage.ImageName, ":")[0], strings.Split(imageDigests[i], "@")[1])
		deployImage := fmt.Sprintf("%s@%s", strings.Split(pulledImage.ImageName, ":")[0], imageDigests[i])
		deployImages[pulledImage.ImageName] = deployImage
	}
	return deployImages, nil
}

// makeDeployment is a smart constructor for creating a Deployment
func makeDeployment(bp verify.Blueprint, streamletID string, streamlet cloudflowapplication.Streamlet,
	deployImages map[string]string, replicas map[string]int) (cloudflowapplication.Deployment, error) {

	var deployment cloudflowapplication.Deployment
	deployment.StreamletName = streamletID
	deployment.ClassName = streamlet.Descriptor.ClassName
	image, err := bp.GetImageFromStreamletID(streamletID)
	if err != nil {
		return cloudflowapplication.Deployment{}, err
	}

	deployment.Image = deployImages[image]
	deployment.PortMappings = getAllPortMappings(bp.GetName(), streamletID, streamlet.Descriptor, bp)
	deployment.VolumeMounts = streamlet.Descriptor.VolumeMounts
	deployment.Runtime = streamlet.Descriptor.Runtime
	deployment.SecretName = transformToDNS1123SubDomain(streamlet.Name)
	deployment.Name = fmt.Sprintf("%s.%s", bp.GetName(), streamletID)
	if scale, ok := replicas[streamletID]; ok {
		deployment.Replicas = &scale
	}

	config, endpoint := getServerConfigAndEndpoint(bp.GetName(), streamlet)
	if config != nil {
		deployment.Config = config
	}
	if endpoint != (cloudflowapplication.Endpoint{}) {
		deployment.Endpoint = &endpoint
	}
	return deployment, nil
}

// Removes from the leading and trailing positions the specified characters.
func trim(name string) string {
	return strings.TrimSuffix(strings.TrimSuffix(strings.TrimPrefix(strings.TrimPrefix(name, "."), "-"), "."), "-")
}

// Make a name compatible with DNS 1123 Subdomain
// Limit the resulting name to 253 characters
func transformToDNS1123SubDomain(name string) string {
	splits := strings.Split(name, ".")
	var normalized []string
	for _, split := range splits {
		normalized = append(normalized, trim(normalize(split)))
	}
	joined := strings.Join(normalized[:], ".")
	if len(joined) > subDomainMaxLength {
		return strings.TrimSuffix(joined[0:subDomainMaxLength], ".")
	}
	return strings.TrimSuffix(joined, ".")
}

func normalize(name string) string {
	t := transform.Chain(norm.NFKD)
	normalizedName, _, _ := transform.String(t, name)
	s := strings.ReplaceAll(strings.ReplaceAll(strings.ToLower(normalizedName), "_", "-"), ".", "-")
	var re = regexp.MustCompile(`[^-a-z0-9]`)
	return re.ReplaceAllString(s, "")
}

func getServerAttribute(descriptor cloudflowapplication.Descriptor) cloudflowapplication.Attribute {
	var attr cloudflowapplication.Attribute
	for _, attribute := range descriptor.Attributes {
		if attribute.AttributeName == "server" {
			attr = attribute
			return attribute
		}
	}
	return attr
}

// MinimumEndpointContainerPort indicates the minimum value of the end point port
const endpointContainerPort = 3000
const subDomainMaxLength = 253

type configRoot struct {
	Cloudflow internal `json:"cloudflow,omitempty"`
}
type internal struct {
	Internal server `json:"internal,omitempty"`
}
type server struct {
	Server containerPort `json:"server,omitempty"`
}
type containerPort struct {
	ContainerPort int `json:"container-port,omitempty"`
}

func getServerConfigAndEndpoint(appID string, streamlet cloudflowapplication.Streamlet) (json.RawMessage, cloudflowapplication.Endpoint) {
	var endPoint cloudflowapplication.Endpoint
	attribute := getServerAttribute(streamlet.Descriptor)
	if attribute == (cloudflowapplication.Attribute{}) {
		x, _ := json.Marshal(containerPort{})
		return x, cloudflowapplication.Endpoint{}
	}
	endPoint = cloudflowapplication.Endpoint{AppID: appID, Streamlet: streamlet.Name, ContainerPort: endpointContainerPort}
	c := containerPort{ContainerPort: endpointContainerPort}
	s := server{Server: c}
	i := internal{Internal: s}
	r := configRoot{Cloudflow: i}
	bytes, _ := json.Marshal(r)
	return bytes, endPoint
}

func getAllPortMappings(appID string, streamletName string, descriptor cloudflowapplication.Descriptor,
	blueprint verify.Blueprint) map[string]cloudflowapplication.PortMapping {
	allPortMappings := make(map[string]cloudflowapplication.PortMapping)

	for k, v := range getOutletPortMappings(appID, streamletName, descriptor) {
		allPortMappings[k] = v
	}
	for k, v := range getInletPortMappings(appID, streamletName, blueprint) {
		allPortMappings[k] = v
	}
	return allPortMappings
}

func getOutletPortMappings(appID string, streamletName string, descriptor cloudflowapplication.Descriptor) map[string]cloudflowapplication.PortMapping {
	portMappings := make(map[string]cloudflowapplication.PortMapping)
	for _, outlet := range descriptor.Outlets {
		portMappings[outlet.Name] = cloudflowapplication.PortMapping{AppID: appID, Streamlet: streamletName, Outlet: outlet.Name}
	}
	return portMappings
}

func getInletPortMappings(appID string, streamletName string, blueprint verify.Blueprint) map[string]cloudflowapplication.PortMapping {
	portMappings := make(map[string]cloudflowapplication.PortMapping)
	for _, conn := range blueprint.GetConnections() {
		portMappings[conn.InletName] =
			cloudflowapplication.PortMapping{AppID: appID, Outlet: conn.OutletName, Streamlet: conn.OutletStreamletName}
	}
	return portMappings
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
func AppendExistingValuesNotConfigured(client *kubernetes.Clientset, spec cloudflowapplication.CloudflowApplicationSpec, configurationKeyValues map[string]string) map[string]string {
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
func AppendDefaultValuesForMissingConfigurationValues(spec cloudflowapplication.CloudflowApplicationSpec, configurationKeyValues map[string]string) map[string]string {
	for _, streamlet := range spec.Streamlets {
		for _, descriptor := range streamlet.Descriptor.ConfigParameters {
			fqKey := prefixWithStreamletName(streamlet.Name, descriptor.Key)
			if _, ok := configurationKeyValues[fqKey]; !ok {
				if descriptor.DefaultValue != nil && len(*descriptor.DefaultValue) > 0 {
					fmt.Printf("Default value '%s' will be used for configuration parameter '%s'\n", *descriptor.DefaultValue, fqKey)
					configurationKeyValues[fqKey] = *descriptor.DefaultValue
				}
			}
		}
	}
	return configurationKeyValues
}

// ValidateVolumeMounts validates that volume mounts command line arguments corresponds to a volume mount descriptor in the AD and that the PVC named in the argument exists
func ValidateVolumeMounts(k8sClient *kubernetes.Clientset, spec cloudflowapplication.CloudflowApplicationSpec, volumeMountPVCNameArray []string) (cloudflowapplication.CloudflowApplicationSpec, error) {
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
func ValidateConfigurationAgainstDescriptor(spec cloudflowapplication.CloudflowApplicationSpec, configurationKeyValues map[string]string) (map[string]string, error) {

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

func validateStreamletConfigKey(descriptor cloudflowapplication.ConfigParameterDescriptor, value string) error {
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
		if descriptor.Pattern == nil {
			return fmt.Errorf("The regular expression pattern is empty")
		}
		r, err := regexp.Compile(*descriptor.Pattern)
		if err != nil {
			return fmt.Errorf("the regular expression pattern failed to compile: %s", err.Error())
		}

		if !r.MatchString(value) {
			return fmt.Errorf("Value `%s` does not match the regular expression `%s`.", value, *descriptor.Pattern)
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
func CreateSecretsData(spec *cloudflowapplication.CloudflowApplicationSpec, configurationKeyValues map[string]string) map[string]*corev1.Secret {
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

func findSecretName(spec *cloudflowapplication.CloudflowApplicationSpec, streamletName string) string {
	for _, deployment := range spec.Deployments {
		if deployment.StreamletName == streamletName {
			return deployment.SecretName
		}
	}
	panic(fmt.Errorf("could not find secret name for streamlet %s", streamletName))
}

func createSecret(appID string, name string, data map[string]string) *corev1.Secret {
	labels := cloudflowapplication.CreateLabels(appID)
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
