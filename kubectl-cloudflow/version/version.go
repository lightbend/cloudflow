package version

import (
	"errors"
	"strconv"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

// BuildNumber describes the build number
var BuildNumber = "local build"

// ReleaseTagSnapshot is used to check if the ReleaseTag is set to snapshot without the need to duplicate the string
var ReleaseTagSnapshot = "SNAPSHOT"

// ReleaseTag is the tag used for a release, this tag is used to fetch the matching examples repository for this version of the CLI
var ReleaseTag = ReleaseTagSnapshot

// ProtocolVersion is the protocol version, which is shared between the cloudflow-operator and kubectl-cloudflow. The cloudflow-operator creates
// a configmap on bootstrap that kubectl-cloudflow reads to verify that it is compatible.
const ProtocolVersion = "1"

// ProtocolVersionKey is the key of the protocol version in the configmap
const ProtocolVersionKey = "protocol-version"

// ProtocolVersionConfigMapName is the name of the configmap that contains the protocol-version
const ProtocolVersionConfigMapName = "cloudflow-protocol-version"

// GetProtocolVersionConfigMap Get the protocol version config map set by the operator
func GetProtocolVersionConfigMap() (*corev1.ConfigMap, error) {
	k8sClient, k8sErr := k8s.GetClient()
	if k8sErr != nil {
		util.LogAndExit("Failed to create new kubernetes client, %s", k8sErr.Error())
	}
	labelSelector := metav1.LabelSelector{MatchLabels: map[string]string{ProtocolVersionConfigMapName: ProtocolVersionConfigMapName}}

	var cm *corev1.ConfigMap
	configMaps, err := k8sClient.CoreV1().ConfigMaps("").List(
		metav1.ListOptions{LabelSelector: labels.Set(labelSelector.MatchLabels).String()})

	if err == nil {
		if len(configMaps.Items) > 1 {
			return nil, errors.New("Multiple Cloudflow operators detected in the cluster. This is not supported. Exiting")
		}
		if len(configMaps.Items) < 1 {
			return nil, errors.New("No Cloudflow operators detected in the cluster. Exiting")
		}
		return &configMaps.Items[0], nil
	}
	return cm, err
}

// FindCloudflowNamespace tries to find the Cloudflow namespace set in the protocol version config map
func FindCloudflowNamespace() (string, error) {
	cm, err := GetProtocolVersionConfigMap()
	if err != nil {
		util.LogAndExit("Could not find the Cloudflow namespace. Kubernetes API returned an error: %s", err)
	}

	if cm == nil {
		util.LogAndExit("Cannot find the '%s' ConfigMap and/or the Cloudflow namespace. Please make sure that the Cloudflow operator is installed", ProtocolVersionConfigMapName)
	}
	return cm.GetObjectMeta().GetNamespace(), err
}

// FailOnProtocolVersionMismatch fails and exits if the protocol version of kubectl-cloudflow does not match with the cloudflow operator protocol version.
func FailOnProtocolVersionMismatch() {
	cm, err := GetProtocolVersionConfigMap()
	if err != nil {
		util.LogAndExit("Could not verify protocol version. Kubernetes API returned an error: %s", err)
	}

	if cm == nil {
		util.LogAndExit("Cannot find the '%s' ConfigMap, please make sure that the Cloudflow operator is installed", ProtocolVersionConfigMapName)
	}

	operatorProtocolVersion := cm.Data[ProtocolVersionKey]
	if operatorProtocolVersion != ProtocolVersion {
		if version, err := strconv.Atoi(operatorProtocolVersion); err == nil {
			if supportedVersion, err := strconv.Atoi(ProtocolVersion); err == nil {
				if version < supportedVersion {
					util.LogAndExit("This version of kubectl Cloudflow is not compatible with the Cloudflow operator, please upgrade kubectl cloudflow")
				}
				if version > supportedVersion {
					util.LogAndExit("This version of kubectl Cloudflow is not compatible with the Cloudflow operator, please upgrade the Cloudflow operator")
				}
			}
		}
		util.LogAndExit("This version of kubectl Cloudflow is not compatible with the Cloudflow operator, please upgrade kubectl cloudflow")
	}
}
