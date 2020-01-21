package scale

import (
	"encoding/json"
	"os"
	"testing"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cfapp"
	"github.com/stretchr/testify/assert"
)

func TestMain(m *testing.M) {
	code := m.Run()
	os.Exit(code)
}
func Test_updateDeploymentWithReplicas(t *testing.T) {

	applicationConfiguration := cfapp.TestApplicationDescriptor()

	var spec cfapp.CloudflowApplicationSpec
	json.Unmarshal([]byte(applicationConfiguration), &spec)

	spec, err := UpdateDeploymentWithReplicas(spec, "invalid-logger", 2)
	assert.Empty(t, err)
	assert.Equal(t, spec.Deployments[0].Replicas, 2)
	assert.Empty(t, spec.Deployments[1].Replicas)
}
