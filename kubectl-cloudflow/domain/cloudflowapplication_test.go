package domain

import (
	"encoding/json"
	"fmt"
	"os"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestMain(m *testing.M) {
	code := m.Run()
	os.Exit(code)
}

func Test_validateCloudflowApplicationSpec(t *testing.T) {
	applicationConfiguration := TestApplicationDescriptor()
	var app CloudflowApplicationSpec
	jsonError := json.Unmarshal([]byte(applicationConfiguration), &app)

	assert.Empty(t, jsonError)
	assert.Equal(t, app.AppID, "sensor-data-scala")
	assert.Equal(t, app.AppVersion, "478-c0bd57f")

	assert.NotEmpty(t, app.Connections)
	assert.NotEmpty(t, app.Deployments)
	assert.NotEmpty(t, app.Deployments[0].Config)
	assert.NotEmpty(t, app.Streamlets)

	// check that we deserialized everything correctly
	streamlet1, err := findDescriptor("valid-logger", app)
	assert.Empty(t, err)
	assert.True(t, streamlet1.Descriptor.ConfigParameters[0].Key == "log-level")
	assert.True(t, streamlet1.Descriptor.ConfigParameters[1].Key == "msg-prefix")

	fileIngress, err := findDescriptor("file-ingress", app)
	assert.Empty(t, err)
	assert.True(t, fileIngress.Descriptor.VolumeMounts[0].Name == "source-data-mount")
	assert.True(t, fileIngress.Descriptor.VolumeMounts[0].Path == "/mnt/data")
	assert.True(t, fileIngress.Descriptor.VolumeMounts[0].AccessMode == "ReadWriteMany")

	fileIngressDeployment, err := findDeployment("sensor-data-scala.file-ingress", app)
	assert.Empty(t, err)
	assert.True(t, fileIngressDeployment.VolumeMounts[0].Name == "source-data-mount")
	assert.True(t, fileIngressDeployment.VolumeMounts[0].Path == "/mnt/data")
	assert.True(t, fileIngressDeployment.VolumeMounts[0].AccessMode == "ReadWriteMany")
}

func Test_validateOwnerReferenceGeneration(t *testing.T) {
	applicationConfiguration := TestApplicationDescriptor()
	var app CloudflowApplicationSpec
	jsonError := json.Unmarshal([]byte(applicationConfiguration), &app)
	assert.Empty(t, jsonError)

	cr := NewCloudflowApplication(app)

	ownerReference := cr.GenerateOwnerReference()
	assert.True(t, ownerReference.Kind == "CloudflowApplication")
	assert.True(t, ownerReference.APIVersion == "cloudflow.lightbend.com/v1alpha1")
	assert.True(t, ownerReference.Name == "sensor-data-scala")

}

func findDescriptor(name string, app CloudflowApplicationSpec) (Streamlet, error) {
	for _, v := range app.Streamlets {
		if v.Name == name {
			return v, nil
		}
	}
	err := fmt.Errorf("Could not find streamlet descriptor %s", name)
	return Streamlet{}, err
}

func findDeployment(name string, app CloudflowApplicationSpec) (Deployment, error) {
	for _, v := range app.Deployments {
		if v.Name == name {
			return v, nil
		}
	}
	err := fmt.Errorf("Could not find streamlet deployment %s", name)
	return Deployment{}, err
}
