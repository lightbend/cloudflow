package volume

import (
	"fmt"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cfapp"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"k8s.io/client-go/kubernetes"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

const cloudflowStreamletsPrefix = "cloudflow.streamlets"

// TODO change how volume mounts are done.
func volumePrefixWithStreamletName(streamletName string, key string) string {
	return fmt.Sprintf("%s.%s.%s", cloudflowStreamletsPrefix, streamletName, key)
}

// ValidateVolumeMounts validates that volume mounts command line arguments corresponds to a volume mount descriptor in the AD and that the PVC named in the argument exists
func ValidateVolumeMounts(k8sClient *kubernetes.Clientset, spec cfapp.CloudflowApplicationSpec, volumeMountPVCNameArray []string) (cfapp.CloudflowApplicationSpec, error) {
	// TODO change how volume mounts are configured.
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
			volumeMountName := volumePrefixWithStreamletName(streamlet.Name, mount.Name)

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
