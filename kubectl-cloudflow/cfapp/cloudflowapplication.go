package cfapp

import (
	"encoding/json"
	"fmt"
	"strconv"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/fileutil"
	. "github.com/lightbend/cloudflow/kubectl-cloudflow/version"
	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

// PortMapping maps outlets
type PortMapping struct {
	ID     string          `json:"id"`
	Config json.RawMessage `json:"config"`
}

// Endpoint contains deployment endpoint information
type Endpoint struct {
	AppID         string `json:"app_id,omitempty"`
	Streamlet     string `json:"streamlet,omitempty"`
	ContainerPort int    `json:"container_port,omitempty"`
}

// Deployment contains a streamlet deployment
type Deployment struct {
	ClassName     string                  `json:"class_name"`
	Config        json.RawMessage         `json:"config"`
	Image         string                  `json:"image"`
	Name          string                  `json:"name"`
	PortMappings  map[string]PortMapping  `json:"port_mappings"`
	VolumeMounts  []VolumeMountDescriptor `json:"volume_mounts"`
	Runtime       string                  `json:"runtime"`
	StreamletName string                  `json:"streamlet_name"`
	SecretName    string                  `json:"secret_name"`
	Endpoint      *Endpoint               `json:"endpoint,omitempty"`
	Replicas      int                     `json:"replicas,omitempty"`
}

// InOutletSchema contains the schema of a in/out-let
type InOutletSchema struct {
	Fingerprint string `json:"fingerprint"`
	Schema      string `json:"schema"`
	Name        string `json:"name"`
	Format      string `json:"format"`
}

// InOutlet defines a in/out-let and its schema
type InOutlet struct {
	Name   string         `json:"name"`
	Schema InOutletSchema `json:"schema"`
}

// Attribute describes that a streamlet requires specific configuration by the cloudflow platform
type Attribute struct {
	AttributeName string `json:"attribute_name"`
	ConfigPath    string `json:"config_path"`
}

// ConfigParameterDescriptor describes an argument that has to be supplied when deploying a Cloudflow application
type ConfigParameterDescriptor struct {
	Key          string `json:"key"`
	Description  string `json:"description"`
	Type         string `json:"validation_type"`
	Pattern      string `json:"validation_pattern"`
	DefaultValue string `json:"default_value"`
}

// ReadWriteMany is a name of a VolumeMount access mode
const ReadWriteMany = "ReadWriteMany"

// ReadOnlyMany is a name of a VolumeMount access mode
const ReadOnlyMany = "ReadOnlyMany"

// VolumeMountDescriptor describes a volume mount that a streamlet will use
type VolumeMountDescriptor struct {
	Name       string `json:"name"`
	Path       string `json:"path"`
	AccessMode string `json:"access_mode"`
	PVCName    string `json:"pvc_name,omitempty"`
}

// Descriptor describes a streamlet
type Descriptor struct {
	Attributes       []Attribute                 `json:"attributes"`
	ClassName        string                      `json:"class_name"`
	ConfigParameters []ConfigParameterDescriptor `json:"config_parameters"`
	VolumeMounts     []VolumeMountDescriptor     `json:"volume_mounts"`
	Inlets           []InOutlet                  `json:"inlets"`
	Labels           []string                    `json:"labels"`
	Outlets          []InOutlet                  `json:"outlets"`
	Runtime          string                      `json:"runtime"`
	Description      string                      `json:"description"`
}

// Streamlet is a streamlet instance in the cloudflow application
type Streamlet struct {
	Descriptor Descriptor `json:"descriptor"`
	Name       string     `json:"name"`
}

// CloudflowApplicationSpec contains the CR spec for a cloudflow application
type CloudflowApplicationSpec struct {
	AppID          string            `json:"app_id"`
	AppVersion     string            `json:"app_version"`
	Deployments    []Deployment      `json:"deployments"`
	Streamlets     []Streamlet       `json:"streamlets"`
	AgentPaths     map[string]string `json:"agent_paths"`
	Version        string            `json:"version,omitempty"`
	LibraryVersion string            `json:"library_version,omitempty"`
}

// PodStatus contains the status of the pod
type PodStatus struct {
	Name                string `json:"name"`
	Ready               string `json:"ready"`
	NrOfContainersReady int    `json:"nr_of_containers_ready"`
	NrOfContainers      int    `json:"nr_of_containers"`
	Restarts            int    `json:"restarts"`
	Status              string `json:"status"`
}

// StreamletStatus contains the status of the streamlet
type StreamletStatus struct {
	StreamletName string      `json:"streamlet_name"`
	PodStatuses   []PodStatus `json:"pod_statuses"`
}

// EndpointStatus contains the status of the endpoint
type EndpointStatus struct {
	StreamletName string `json:"streamlet_name"`
	URL           string `json:"url"`
}

// CloudflowApplicationStatus contains the status of the application
type CloudflowApplicationStatus struct {
	AppID             string            `json:"app_id"`
	AppVersion        string            `json:"app_version"`
	AppStatus         string            `json:"app_status"`
	EndpointStatuses  []EndpointStatus  `json:"endpoint_statuses"`
	StreamletStatuses []StreamletStatus `json:"streamlet_statuses"`
}

// CloudflowApplication is the complete resource object sent to the API endpoint
type CloudflowApplication struct {
	metav1.TypeMeta
	metav1.ObjectMeta `json:"metadata"`
	Spec              CloudflowApplicationSpec    `json:"spec"`
	Status            *CloudflowApplicationStatus `json:"status,omitempty"`
}

// CloudflowApplicationList is a list of CloudflowApplications
type CloudflowApplicationList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata"`
	Items           []CloudflowApplication `json:"items"`
}

// CloudflowApplicationDescriptorDigestPair is a pair of app descriptor and image digest
type CloudflowApplicationDescriptorDigestPair struct {
	AppDescriptor string `json:"appDescriptor"`
	ImageDigest   string `json:"imageDigest"`
}

// APIVersion formats the correct APIVersion from group name and version
func APIVersion() string {
	return fmt.Sprintf("%s/%s", GroupName, GroupVersion)
}

// LoadCloudflowApplicationSpec loads the spec from file
func LoadCloudflowApplicationSpec(crFile string) (CloudflowApplicationSpec, error) {
	crString, err := fileutil.GetFileContents(crFile)
	if err != nil {
		return CloudflowApplicationSpec{}, fmt.Errorf("Failed to read the file contents for the CR - please check if the file exists or it has a bad formatting (%s)", err.Error())
	}

	bytes := []byte(crString)

	var cr CloudflowApplication
	if json.Unmarshal(bytes, &cr); err != nil {
		return CloudflowApplicationSpec{}, err
	}

	applicationSpec := cr.Spec
	err = checkApplicationDescriptorVersion(applicationSpec)
	return applicationSpec, err
}

// UpdateCloudflowApplication creates a CloudflowApplication struct that can be used with a Update call
func UpdateCloudflowApplication(spec CloudflowApplicationSpec, currentAppCR CloudflowApplication, releaseTag string, buildNumber string) CloudflowApplication {

	app := CloudflowApplication{}
	app.APIVersion = APIVersion()
	app.Kind = "CloudflowApplication"
	app.ObjectMeta = metav1.ObjectMeta{
		Name:            spec.AppID,
		ResourceVersion: currentAppCR.ObjectMeta.ResourceVersion,
		Labels:          CreateLabels(spec.AppID),
		Annotations:     LastModifiedByCLIAnnotation(releaseTag, buildNumber, currentAppCR.ObjectMeta.Annotations),
	}
	app.Spec = spec
	return app
}

// NewCloudflowApplication creates a CloudflowApplication strcut that can be used with a Create call
func NewCloudflowApplication(spec CloudflowApplicationSpec, releaseTag string, buildNumber string) CloudflowApplication {

	app := CloudflowApplication{}
	app.APIVersion = APIVersion()
	app.Kind = Kind
	app.ObjectMeta = metav1.ObjectMeta{
		Name:        spec.AppID,
		Labels:      CreateLabels(spec.AppID),
		Annotations: CreatedByCliAnnotation(releaseTag, buildNumber),
	}
	app.Spec = spec
	return app
}

// NewCloudflowApplicationNamespace creates a Namespace struct
func NewCloudflowApplicationNamespace(spec CloudflowApplicationSpec, releaseTag string, buildNumber string) v1.Namespace {
	return v1.Namespace{
		ObjectMeta: metav1.ObjectMeta{
			Name:   spec.AppID,
			Labels: CreateLabels(spec.AppID),
		},
	}
}

// CreatedByCliAnnotation creates a map for use as an annotation when a resource is created by the CLI
func CreatedByCliAnnotation(releaseTag string, buildNumber string) map[string]string {
	return map[string]string{
		"com.lightbend.cloudflow/created-by-cli-version": fmt.Sprintf("%s (%s)", releaseTag, buildNumber),
	}
}

// LastModifiedByCLIAnnotation creates a map for use as an annotation when a resource is updated by the CLI
func LastModifiedByCLIAnnotation(releaseTag string, buildNumber string, previousAnnotations map[string]string) map[string]string {

	newAnnotations := make(map[string]string)
	for k, v := range previousAnnotations {
		newAnnotations[k] = v
	}
	newAnnotations["com.lightbend.cloudflow/last-modified-by-cli-version"] = fmt.Sprintf("%s (%s)", releaseTag, buildNumber)
	return newAnnotations
}

// CreateLabels creates cloudflow application labels
func CreateLabels(appID string) map[string]string {
	return map[string]string{
		"app.kubernetes.io/part-of":      appID,
		"app.kubernetes.io/managed-by":   "cloudflow",
		"com.lightbend.cloudflow/app-id": appID,
	}
}

// DeepCopyInto copies all properties of this object into another object of the
// same type that is provided as a pointer.
func (in *CloudflowApplication) DeepCopyInto(out *CloudflowApplication) {
	out.TypeMeta = in.TypeMeta
	out.ObjectMeta = in.ObjectMeta
	out.Kind = in.Kind
	out.Spec = CloudflowApplicationSpec{
		AppID:       in.Spec.AppID,
		AppVersion:  in.Spec.AppVersion,
		Deployments: in.Spec.Deployments,
		Streamlets:  in.Spec.Streamlets,
	}
}

// DeepCopyObject returns a generically typed copy of an object
func (in *CloudflowApplication) DeepCopyObject() runtime.Object {
	out := CloudflowApplication{}
	in.DeepCopyInto(&out)

	return &out
}

// DeepCopyObject returns a generically typed copy of an object
func (in *CloudflowApplicationList) DeepCopyObject() runtime.Object {
	out := CloudflowApplicationList{}
	out.TypeMeta = in.TypeMeta
	out.ListMeta = in.ListMeta

	if in.Items != nil {
		out.Items = make([]CloudflowApplication, len(in.Items))
		for i := range in.Items {
			in.Items[i].DeepCopyInto(&out.Items[i])
		}
	}

	return &out
}

// GenerateOwnerReference creates an ownerReference pointing towards this CR
func (in *CloudflowApplication) GenerateOwnerReference() metav1.OwnerReference {

	managingController := true
	blockOwnerDeletion := true
	return metav1.OwnerReference{
		APIVersion:         APIVersion(),
		Kind:               Kind,
		Name:               in.GetName(),
		UID:                in.GetUID(),
		Controller:         &managingController,
		BlockOwnerDeletion: &blockOwnerDeletion}

}

//CheckApplicationDescriptorVersion checks the version, logs and exits if not supported
func checkApplicationDescriptorVersion(spec CloudflowApplicationSpec) error {
	if spec.Version != SupportedApplicationDescriptorVersion {
		// If the version is an int, compare them, otherwise provide a more general message.
		if version, err := strconv.Atoi(spec.Version); err == nil {
			if supportedVersion, err := strconv.Atoi(SupportedApplicationDescriptorVersion); err == nil {
				if version < supportedVersion {
					if spec.LibraryVersion != "" {
						return fmt.Errorf("Application built with sbt-cloudflow version '%s', is incompatible and no longer supported. Please upgrade sbt-cloudflow and rebuild the application with 'sbt buildApp'", spec.LibraryVersion)
					}
					return fmt.Errorf("Application is incompatible and no longer supported. Please upgrade sbt-cloudflow and rebuild the application with 'sbt buildApp'")
				}
				if version > supportedVersion {
					if spec.LibraryVersion != "" {
						return fmt.Errorf("Application built with sbt-cloudflow version '%s', is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again", spec.LibraryVersion)
					}
					return fmt.Errorf("Application is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again")
				}
			}
		}

		return fmt.Errorf("Application is incompatible and no longer supported. Please update sbt-cloudflow and rebuild the application with 'sbt buildApp'")
	}
	return nil
}
