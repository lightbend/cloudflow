package scale

import (
	"fmt"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

// UpdateDeploymentWithReplicas updates a deployment for a specific streamlet with a new value for replicas
func UpdateDeploymentWithReplicas(spec domain.CloudflowApplicationSpec, streamletName string, replicas int) (domain.CloudflowApplicationSpec, error) {
	for i := range spec.Deployments {
		if spec.Deployments[i].StreamletName == streamletName {
			spec.Deployments[i].Replicas = replicas
			return spec, nil
		}
	}

	err := fmt.Errorf("Could not find streamlet %s", streamletName)
	return spec, err
}
