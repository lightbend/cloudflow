package scale

import (
	"fmt"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cfapp"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/printutil"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

// UpdateDeploymentWithReplicas updates a deployment for a specific streamlet with a new value for replicas
func UpdateDeploymentWithReplicas(spec cfapp.CloudflowApplicationSpec, streamletName string, replicas int) (cfapp.CloudflowApplicationSpec, error) {
	for i := range spec.Deployments {
		if spec.Deployments[i].StreamletName == streamletName {
			spec.Deployments[i].Replicas = replicas
			return spec, nil
		}
	}

	err := fmt.Errorf("could not find streamlet %s", streamletName)
	return spec, err
}

// UpdateReplicas updates the deployment replicas in application spec with values from replicasByStreamletName
func UpdateReplicas(applicationClient *cfapp.CloudflowApplicationClient, applicationSpec cfapp.CloudflowApplicationSpec, replicasByStreamletName map[string]int) cfapp.CloudflowApplicationSpec {

	applicationSpec, err := copyReplicaConfigurationFromCurrentApplication(applicationClient, applicationSpec)
	if err != nil {
		printutil.LogAndExit("The application descriptor is invalid, %s", err.Error())
	}

	// update deployment with replicas passed through command line
	for _, deployment := range applicationSpec.Deployments {
		if s, ok := replicasByStreamletName[deployment.StreamletName]; ok {
			if spec, err := UpdateDeploymentWithReplicas(applicationSpec, deployment.StreamletName, s); err != nil {
				printutil.LogAndExit("Cannot set replicas for streamlet [%s] %s", deployment.StreamletName, err.Error())
			} else {
				applicationSpec = spec
			}
		}
	}
	return applicationSpec
}

func copyReplicaConfigurationFromCurrentApplication(applicationClient *cfapp.CloudflowApplicationClient, spec cfapp.CloudflowApplicationSpec) (cfapp.CloudflowApplicationSpec, error) {

	app, err := applicationClient.Get(spec.AppID)
	if err != nil {
		// Not found
		return spec, nil
	}

	replicas := make(map[string]int)
	for i := range app.Spec.Deployments {
		for _, newDeployment := range spec.Deployments {
			if app.Spec.Deployments[i].StreamletName == newDeployment.StreamletName {
				replicas[app.Spec.Deployments[i].StreamletName] = app.Spec.Deployments[i].Replicas
			}
		}
	}

	for name, replicas := range replicas {
		if spec, err := UpdateDeploymentWithReplicas(spec, name, replicas); err != nil {
			return spec, err
		}
	}

	return spec, nil
}
