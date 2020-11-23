package cfapp

import (
	"encoding/json"
	"fmt"
	"os"
	"testing"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/version"
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

	cr := NewCloudflowApplication(app, "v1.3.1", "34ab342")

	ownerReference := cr.GenerateOwnerReference()
	assert.True(t, cr.GetObjectMeta().GetAnnotations()["com.lightbend.cloudflow/created-by-cli-version"] == "v1.3.1 (34ab342)")
	assert.True(t, ownerReference.Kind == "CloudflowApplication")
	assert.True(t, ownerReference.APIVersion == "cloudflow.lightbend.com/v1alpha1")
	assert.True(t, ownerReference.Name == "sensor-data-scala")
	assert.True(t, *ownerReference.Controller == true)
	assert.True(t, *ownerReference.BlockOwnerDeletion == true)

	// Test updating the annotation
	cr = UpdateCloudflowApplication(cr.Spec, cr, "v1.3.2", "35ac352")
	assert.True(t, cr.GetObjectMeta().GetAnnotations()["com.lightbend.cloudflow/created-by-cli-version"] == "v1.3.1 (34ab342)")
	assert.True(t, cr.GetObjectMeta().GetAnnotations()["com.lightbend.cloudflow/last-modified-by-cli-version"] == "v1.3.2 (35ac352)")
}

func Test_checkApplicationDescriptor(t *testing.T) {
	applicationConfiguration := TestApplicationDescriptor()
	var app CloudflowApplicationSpec
	jsonError := json.Unmarshal([]byte(applicationConfiguration), &app)
	assert.Empty(t, jsonError)
	assert.Nil(t, checkApplicationDescriptorVersion(app))
	older := app
	older.Version = fmt.Sprintf("%d", version.SupportedApplicationDescriptorVersion-1)
	assert.Equal(t, checkApplicationDescriptorVersion(older).Error(), "Application built with sbt-cloudflow version '2.0.18', is incompatible and no longer supported. Please upgrade sbt-cloudflow and rebuild the application with 'sbt buildApp'")
	newer := app
	newer.Version = fmt.Sprintf("%d", version.SupportedApplicationDescriptorVersion+1)
	assert.Equal(t, checkApplicationDescriptorVersion(newer).Error(), "Application built with sbt-cloudflow version '2.0.18', is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again")
	missingVersion := app
	missingVersion.Version = ""
	assert.Equal(t, checkApplicationDescriptorVersion(missingVersion).Error(), "Application file parse error: spec.version is missing or empty")
	invalidApp := app
	invalidApp.Version = "5b"
	assert.Equal(t, checkApplicationDescriptorVersion(invalidApp).Error(), "Application file parse error: spec.version is invalid")
	missingLibraryVersion := app
	missingLibraryVersion.LibraryVersion = ""
	assert.Equal(t, checkApplicationDescriptorVersion(missingLibraryVersion).Error(), "Application file parse error: spec.library_version is missing or empty")
}

func findDescriptor(name string, app CloudflowApplicationSpec) (Streamlet, error) {
	for _, v := range app.Streamlets {
		if v.Name == name {
			return v, nil
		}
	}
	err := fmt.Errorf("could not find streamlet descriptor %s", name)
	return Streamlet{}, err
}

func findDeployment(name string, app CloudflowApplicationSpec) (Deployment, error) {
	for _, v := range app.Deployments {
		if v.Name == name {
			return v, nil
		}
	}
	err := fmt.Errorf("could not find streamlet deployment %s", name)
	return Deployment{}, err
}
