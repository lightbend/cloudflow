package cfapp

import (
	"encoding/json"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8sclient"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

// GroupName for our CR
const GroupName = "cloudflow.lightbend.com"

// GroupVersion for our CR
const GroupVersion = "v1alpha1"

// Kind for our CR
const Kind = "CloudflowApplication"

// CloudflowApplicationClient is a REST client that can be used to create or update CloudflowApplication CR
type CloudflowApplicationClient struct {
	restClient rest.Interface
	ns         string
}

func newForConfig(c *rest.Config) (*rest.RESTClient, error) {
	config := *c
	config.ContentConfig.GroupVersion = &schema.GroupVersion{Group: GroupName, Version: GroupVersion}
	config.APIPath = "/apis"
	config.NegotiatedSerializer = serializer.DirectCodecFactory{CodecFactory: scheme.Codecs}
	config.UserAgent = rest.DefaultKubernetesUserAgent()

	client, err := rest.RESTClientFor(&config)
	if err != nil {
		return nil, err
	}

	return client, nil
}

// GetCloudflowApplicationClient returns a CloudflowApplicationClient that can be used to create or update a `CloudflowApplication` CR
func GetCloudflowApplicationClient(namespace string) (*CloudflowApplicationClient, error) {

	kubeconfig := k8sclient.GetKubeConfig()
	config, err := clientcmd.BuildConfigFromFlags("", kubeconfig)
	if err != nil {
		return nil, err
	}

	restClient, err := newForConfig(config)

	return &CloudflowApplicationClient{
		restClient: restClient,
		ns:         namespace,
	}, nil
}

// Create creates a new CloudflowApplication CR
func (c *CloudflowApplicationClient) Create(app CloudflowApplication) (*CloudflowApplication, error) {
	result := CloudflowApplication{}
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
func (c *CloudflowApplicationClient) Get(name string) (*CloudflowApplication, error) {
	result := CloudflowApplication{}
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
func (c *CloudflowApplicationClient) List() (*CloudflowApplicationList, error) {
	result := CloudflowApplicationList{}
	err := c.restClient.
		Get().
		Resource("cloudflowapplications").
		Do().
		Into(&result)

	return &result, err
}

// Update updates a CloudflowApplication CR
func (c *CloudflowApplicationClient) Update(app CloudflowApplication) (*CloudflowApplication, error) {
	result := CloudflowApplication{}
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
