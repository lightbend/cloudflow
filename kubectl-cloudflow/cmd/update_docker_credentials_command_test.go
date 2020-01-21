package cmd

import (
	"testing"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cfapp"
	"github.com/stretchr/testify/assert"
	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func Test_appendCloudflowImagePullSecretName(t *testing.T) {

	appID := "test-app"
	serviceAccount := v1.ServiceAccount{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "cloudflow-app-serviceaccount",
			Namespace: appID,
			Labels:    cfapp.CreateLabels(appID),
		},
	}

	result := appendCloudflowImagePullSecretName(&serviceAccount, "test-secret")
	assert.Equal(t, result.ImagePullSecrets[0].Name, "test-secret")

	result = appendCloudflowImagePullSecretName(&serviceAccount, "test-secret")
	assert.Equal(t, len(result.ImagePullSecrets), 1)

	result = appendCloudflowImagePullSecretName(&serviceAccount, "test-secret")
	result = appendCloudflowImagePullSecretName(result, "some-other-secret")
	assert.Equal(t, result.ImagePullSecrets[0].Name, "test-secret")
	assert.Equal(t, result.ImagePullSecrets[1].Name, "some-other-secret")
}
