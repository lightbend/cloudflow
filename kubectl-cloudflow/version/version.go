package version

import (
	"strconv"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

// BuildNumber describes the build number
var BuildNumber = "local build"

// ReleaseTagSnapshot is used to check if the ReleaseTag is set to snapshot without the need to duplicate the string
var ReleaseTagSnapshot = "SNAPSHOT"

// ReleaseTag is the tag used for a release, this tag is used to fetch the matching examples repository for this version of the CLI
var ReleaseTag = ReleaseTagSnapshot

// ProtocolVersionNamespace is the namespace that must contain the protocol version configmap
const ProtocolVersionNamespace = "cloudflow"

// ProtocolVersion is the protocol version, which is shared between the cloudflow-operator and kubectl-cloudflow. The cloudflow-operator creates
// a configmap on bootstrap that kubectl-cloudflow reads to verify that it is compatible.
const ProtocolVersion = "1"

// ProtocolVersionKey is the key of the protocol version in the configmap
const ProtocolVersionKey = "protocol-version"

// ProtocolVersionConfigMapName is the name of the configmap that contains the protocol-version
const ProtocolVersionConfigMapName = "cloudflow-protocol-version"

// FailOnProtocolVersionMismatch fails and exits if the protocol version of kubectl-cloudflow does not match with the cloudflow operator protocol version.
func FailOnProtocolVersionMismatch() {
	k8sClient, k8sErr := k8s.GetClient()
	if k8sErr != nil {
		util.LogAndExit("Failed to create new kubernetes client, %s", k8sErr.Error())
	}
	var err error
	var cm *corev1.ConfigMap
	if cm, err = k8sClient.CoreV1().ConfigMaps(ProtocolVersionNamespace).Get(ProtocolVersionConfigMapName, metav1.GetOptions{}); err == nil {
		operatorProtocolVersion := cm.Data[ProtocolVersionKey]
		if operatorProtocolVersion != ProtocolVersion {
			if version, err := strconv.Atoi(operatorProtocolVersion); err == nil {
				if supportedVersion, err := strconv.Atoi(ProtocolVersion); err == nil {
					if version < supportedVersion {
						util.LogAndExit("This version of kubectl cloudflow is not compatible with the Cloudflow operator, please upgrade kubectl cloudflow")
					}
					if version > supportedVersion {
						util.LogAndExit("This version of kubectl cloudflow is not compatible with the Cloudflow operator, please upgrade the Cloudflow operator")
					}
				}
			}
			util.LogAndExit("This version of kubectl cloudflow is not compatible with the Cloudflow operator, please upgrade kubectl cloudflow")
		}
	} else if strings.Contains(err.Error(), "not found") {
		util.LogAndExit("Cannot find the '%s' configmap, please make sure that the Cloudflow operator is installed", ProtocolVersionConfigMapName)
	} else {
		util.LogAndExit("Could not verify protocol version. Kubernetes API returned an error: %s", err)
	}
}
