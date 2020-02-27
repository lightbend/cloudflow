package domain

import (
	"encoding/json"
	"fmt"

	v1 "k8s.io/api/core/v1"
	meta_v1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
)

type Pair struct {
	a, b interface{}
}

type ImageReference struct {
	Registry   string
	Repository string
	Image      string
	Tag        string
	FullURI    string
}

// Connection is one inlet/outlet connection
type Connection struct {
	InletName           string `json:"inlet_name"`
	InletStreamletName  string `json:"inlet_streamlet_name"`
	OutletName          string `json:"outlet_name"`
	OutletStreamletName string `json:"outlet_streamlet_name"`
}

// PortMapping maps outlets
type PortMapping struct {
	AppID     string `json:"app_id"`
	Outlet    string `json:"outlet"`
	Streamlet string `json:"streamlet"`
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
}

// Descriptors label value with streamlet descriptors
type Descriptors struct {
	StreamletDescriptors []Descriptor `json:"streamlet-descriptors"`
	APIVersion           string       `json:"api-version"`
}

// Streamlet TBD
type Streamlet struct {
	Descriptor Descriptor `json:"descriptor"`
	Name       string     `json:"name"`
}

// SupportedApplicationDescriptorVersion is the Application Descriptor Version that this version of kubectl-cloudflow supports.
// This version must match up with the version that is added by sbt-cloudflow, which is hardcoded in `cloudflow.blueprint.deployment.ApplicationDescriptor`.
const SupportedApplicationDescriptorVersion = "1"

// CloudflowApplicationSpec TBD
type CloudflowApplicationSpec struct {
	AppID          string            `json:"app_id"`
	AppVersion     string            `json:"app_version"`
	Connections    []Connection      `json:"connections"`
	Deployments    []Deployment      `json:"deployments"`
	Streamlets     []Streamlet       `json:"streamlets"`
	AgentPaths     map[string]string `json:"agent_paths"`
	Version        string            `json:"version,omitempty"`
	LibraryVersion string            `json:"library_version,omitempty"`
}

// PodStatus contains the status of the pod
type PodStatus struct {
	Name     string `json:"name"`
	Ready    string `json:"ready"`
	Restarts int    `json:"restarts"`
	Status   string `json:"status"`
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
	EndpointStatuses  []EndpointStatus  `json:"endpoint_statuses"`
	StreamletStatuses []StreamletStatus `json:"streamlet_statuses"`
}

// CloudflowApplication is the complete resource object sent to the API endpoint
type CloudflowApplication struct {
	meta_v1.TypeMeta
	meta_v1.ObjectMeta `json:"metadata"`
	Spec               CloudflowApplicationSpec   `json:"spec"`
	Status             CloudflowApplicationStatus `json:"status"`
}

// CloudflowApplicationList is a list of CloudflowApplications
type CloudflowApplicationList struct {
	meta_v1.TypeMeta `json:",inline"`
	meta_v1.ListMeta `json:"metadata"`
	Items            []CloudflowApplication `json:"items"`
}

// CloudflowApplicationDescriptorDigestPair is a pair of app descriptor and image digest
type CloudflowApplicationDescriptorDigestPair struct {
	AppDescriptor string `json:"appDescriptor"`
	ImageDigest   string `json:"imageDigest"`
}

// CloudflowStreamletDescriptorsDigestPair is a pair of streamlet descriptors and image digest
type CloudflowStreamletDescriptorsDigestPair struct {
	StreamletDescriptors []Descriptor `json:"streamletDescriptors"`
	ImageDigest          string       `json:"imageDigest"`
}

// CloudflowStreamletDescriptors is an array of descriptors
type CloudflowStreamletDescriptors struct {
	Descriptors []Descriptor `json:"descriptors"`
}

// GroupName for our CR
const GroupName = "cloudflow.lightbend.com"

// GroupVersion for our CR
const GroupVersion = "v1alpha1"

// Kind for our CR
const Kind = "CloudflowApplication"

// ManagedBy label for our CR
const ManagedBy = "cloudflow"

// UpdateCloudflowApplication creates a CloudflowApplication struct that can be used with a Update call
func UpdateCloudflowApplication(spec CloudflowApplicationSpec, resourceVersion string) CloudflowApplication {

	app := CloudflowApplication{}
	app.APIVersion = fmt.Sprintf("%s/%s", GroupName, GroupVersion)
	app.Kind = "CloudflowApplication"
	app.ObjectMeta = meta_v1.ObjectMeta{
		Name:            spec.AppID,
		ResourceVersion: resourceVersion,
		Labels:          CreateLabels(spec.AppID),
	}
	app.Spec = spec
	return app
}

// NewCloudflowApplication creates a CloudflowApplication strcut that can be used with a Create call
func NewCloudflowApplication(spec CloudflowApplicationSpec) CloudflowApplication {

	app := CloudflowApplication{}
	app.APIVersion = fmt.Sprintf("%s/%s", GroupName, GroupVersion)
	app.Kind = Kind
	app.ObjectMeta = meta_v1.ObjectMeta{
		Name:   spec.AppID,
		Labels: CreateLabels(spec.AppID),
	}
	app.Spec = spec
	return app
}

// NewCloudflowApplicationNamespace creates a Namespace struct
func NewCloudflowApplicationNamespace(spec CloudflowApplicationSpec) v1.Namespace {
	return v1.Namespace{
		ObjectMeta: meta_v1.ObjectMeta{
			Name:   spec.AppID,
			Labels: CreateLabels(spec.AppID),
		},
	}
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
		Connections: in.Spec.Connections,
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
