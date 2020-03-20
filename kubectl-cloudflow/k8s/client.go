package k8s

import (
	"encoding/json"
	"os"
	"path/filepath"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"

	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/scheme"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"
)

// GetKubeConfig get the KUBECONFIG path from the KUBECONFIG env var if it's defined, else fallback to default location
func GetKubeConfig() string {
	kubeconfig := os.Getenv("KUBECONFIG")
	if len(kubeconfig) == 0 {
		return filepath.Join(os.Getenv("HOME"), ".kube", "config")
	}

	return kubeconfig
}

// GetClient returns a client built by the `kubernetes` namespace
func GetClient() (*kubernetes.Clientset, error) {

	kubeconfig := GetKubeConfig()
	config, err := clientcmd.BuildConfigFromFlags("", kubeconfig)
	if err != nil {
		util.LogErrorAndExit(err)
	}

	return kubernetes.NewForConfig(config)
}

// GetCloudflowApplicationClient returns a CloudflowApplicationClient that can be used to create or update a `CloudflowApplication` CR
func GetCloudflowApplicationClient(namespace string) (*CloudflowApplicationClient, error) {

	kubeconfig := GetKubeConfig()
	config, err := clientcmd.BuildConfigFromFlags("", kubeconfig)
	if err != nil {
		util.LogErrorAndExit(err)
	}

	restClient, err := newForConfig(config)

	return &CloudflowApplicationClient{
		restClient: restClient,
		ns:         namespace,
	}, nil
}

func newForConfig(c *rest.Config) (*rest.RESTClient, error) {
	config := *c
	config.ContentConfig.GroupVersion = &schema.GroupVersion{Group: cloudflowapplication.GroupName, Version: cloudflowapplication.GroupVersion}
	config.APIPath = "/apis"
	config.NegotiatedSerializer = serializer.DirectCodecFactory{CodecFactory: scheme.Codecs}
	config.UserAgent = rest.DefaultKubernetesUserAgent()

	client, err := rest.RESTClientFor(&config)
	if err != nil {
		return nil, err
	}

	return client, nil
}

// CloudflowApplicationClient is a REST client that can be used to create or update CloudflowApplication CR
type CloudflowApplicationClient struct {
	restClient rest.Interface
	ns         string
}

// Create creates a new CloudflowApplication CR
func (c *CloudflowApplicationClient) Create(app cloudflowapplication.CloudflowApplication) (*cloudflowapplication.CloudflowApplication, error) {
	result := cloudflowapplication.CloudflowApplication{}
	err := c.restClient.
		Post().
		Namespace(c.ns).
		Resource("cloudflowapplications").
		Body(&app).
		Do().
		Into(&result)

	return &result, err
}

// Get returns an already created CloudflowApplication, or an error
func (c *CloudflowApplicationClient) Get(name string) (*cloudflowapplication.CloudflowApplication, error) {
	result := cloudflowapplication.CloudflowApplication{}
	err := c.restClient.
		Get().
		Namespace(c.ns).
		Resource("cloudflowapplications").
		Name(name).
		Do().
		Into(&result)

	return &result, err
}

// Delete a CloudflowApplication successfully and return the rest.Result (a Status), or an error
func (c *CloudflowApplicationClient) Delete(name string) (*rest.Result, error) {
	result := c.restClient.
		Delete().
		Namespace(c.ns).
		Resource("cloudflowapplications").
		Name(name).
		Do()

	err := result.Error()

	return &result, err
}

// List returns a list of CloudflowApplications, or an error
func (c *CloudflowApplicationClient) List() (*cloudflowapplication.CloudflowApplicationList, error) {
	result := cloudflowapplication.CloudflowApplicationList{}
	err := c.restClient.
		Get().
		Resource("cloudflowapplications").
		Do().
		Into(&result)

	return &result, err
}

// Update updates a CloudflowApplication CR
func (c *CloudflowApplicationClient) Update(app cloudflowapplication.CloudflowApplication) (*cloudflowapplication.CloudflowApplication, error) {
	result := cloudflowapplication.CloudflowApplication{}
	bytes, _ := json.Marshal(app)

	err := c.restClient.
		Patch(types.MergePatchType).
		Namespace(c.ns).
		Name(app.Spec.AppID).
		Resource("cloudflowapplications").
		// Note: Using Body(&app) loses the Content-Type header set by Patch.
		Body(bytes).
		Do().
		Into(&result)

	return &result, err
}
