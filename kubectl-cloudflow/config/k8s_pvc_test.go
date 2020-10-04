package config 

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/cfapp"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8sclient"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/printutil"




)



func Test_PVCExists(t *testing.T){
	applicationName := "swiss-knife"
	namespace := "swiss-knife"

	labelConfigSection := newConfig(`
	cloudflow.runtimes.spark.kubernetes.pods.pod {
	  volumes {
	    bar {
	      pvc {
	        name = myclaim
	        readOnly = false
	      } 
	    }
	  }
	}
	`)
	clientSet, k8sErr := k8sclient.GetClient()
	if k8sErr != nil {
		printutil.LogAndExit("Failed to create a new kubernetes client for Cloudflow application `%s`, %s", namespace, k8sErr.Error())
	}
	cloudflowApplicationClient, err := cfapp.GetCloudflowApplicationClient(applicationName)
	if err != nil {
		printutil.LogAndExit("Failed to create new client, %s", err.Error())
	}
	appCR, err := cloudflowApplicationClient.Get("swiss-knife")
	if err != nil {
		printutil.LogAndExit("Failed to retrieve the application `%s`, %s", applicationName, err.Error())
	}
	assert.Empty(t, AllConfigPVCsExist(labelConfigSection, "swiss-knife", appCR.Spec, clientSet))
}