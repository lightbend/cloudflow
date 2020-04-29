package domain

import (
	"encoding/json"
	"fmt"

	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
)

// PortMapping maps outlets
type PortMapping struct {
	AppID            string          `json:"app_id"`
	Name             string          `json:"name"`
	Streamlet        string          `json:"streamlet"`
	Config           json.RawMessage `json:"config"`
	BootstrapServers string          `json:"bootstrap_servers,omitempty"`
	Managed          bool            `json:"managed"`
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

// Attribute TBD
type Attribute struct {
	AttributeName string `json:"attribute_name"`
	ConfigPath    string `json:"config_path"`
}

// ConfigParameterDescriptor TBD
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

// VolumeMountDescriptor TBD
type VolumeMountDescriptor struct {
	Name       string `json:"name"`
	Path       string `json:"path"`
	AccessMode string `json:"access_mode"`
	PVCName    string `json:"pvc_name,omitempty"`
}

// Descriptor TBD
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
	Image            string                      `json:"image"`
}

// Streamlet TBD
type Streamlet struct {
	Descriptor Descriptor `json:"descriptor"`
	Name       string     `json:"name"`
}

// SupportedApplicationDescriptorVersion is the Application Descriptor Version that this version of kubectl-cloudflow supports.
// This version must match up with the version that is added by sbt-cloudflow, which is hardcoded in `cloudflow.blueprint.deployment.ApplicationDescriptor`.
const SupportedApplicationDescriptorVersion = "2"

// CloudflowApplicationSpec TBD
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

// GroupName for our CR
const GroupName = "cloudflow.lightbend.com"

// GroupVersion for our CR
const GroupVersion = "v1alpha1"

// Kind for our CR
const Kind = "CloudflowApplication"

// ManagedBy label for our CR
const ManagedBy = "cloudflow"

// ApiVersion formats the correct ApiVersion from group name and version
func ApiVersion() string {
	return fmt.Sprintf("%s/%s", GroupName, GroupVersion)
}

// UpdateCloudflowApplication creates a CloudflowApplication struct that can be used with a Update call
func UpdateCloudflowApplication(spec CloudflowApplicationSpec, currentAppCR CloudflowApplication, releaseTag string, buildNumber string) CloudflowApplication {

	app := CloudflowApplication{}
	app.APIVersion = ApiVersion()
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
	app.APIVersion = ApiVersion()
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
		APIVersion:         ApiVersion(),
		Kind:               Kind,
		Name:               in.GetName(),
		UID:                in.GetUID(),
		Controller:         &managingController,
		BlockOwnerDeletion: &blockOwnerDeletion}

}
