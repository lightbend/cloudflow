package kafka

import (
	"fmt"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"

	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	"k8s.io/client-go/kubernetes/scheme"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"

	meta_v1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
)

// KafkaTopic is one kafka topic
type KafkaTopic struct {
	meta_v1.TypeMeta
	meta_v1.ObjectMeta `json:"metadata"`
}

type kafakTopicList struct {
	meta_v1.TypeMeta `json:",inline"`
	meta_v1.ListMeta `json:"metadata"`
	Items            []KafkaTopic `json:"items"`
}

// DeepCopyInto copies all properties of this object into another object of the
// same type that is provided as a pointer.
func (in *KafkaTopic) DeepCopyInto(out *KafkaTopic) {
	out.TypeMeta = in.TypeMeta
	out.ObjectMeta = in.ObjectMeta
	out.Kind = in.Kind
}

// DeepCopyObject returns a generically typed copy of an object
func (in *KafkaTopic) DeepCopyObject() runtime.Object {
	out := KafkaTopic{}
	in.DeepCopyInto(&out)

	return &out
}

// DeepCopyObject returns a generically typed copy of an object
func (in *kafakTopicList) DeepCopyObject() runtime.Object {
	out := kafakTopicList{}
	out.TypeMeta = in.TypeMeta
	out.ListMeta = in.ListMeta

	if in.Items != nil {
		out.Items = make([]KafkaTopic, len(in.Items))
		for i := range in.Items {
			in.Items[i].DeepCopyInto(&out.Items[i])
		}
	}

	return &out
}

// GetAllKafkaTopics returns a list of all Kafka topics managed by Piplines for a specific application
func GetAllKafkaTopics(applicationId string) []KafkaTopic {

	config, err := clientcmd.BuildConfigFromFlags("", k8s.GetKubeConfig())
	if err != nil {
		util.LogErrorAndExit(err)
	}

	config.ContentConfig.GroupVersion = &schema.GroupVersion{Group: "kafka.strimzi.io", Version: "v1alpha1"}
	config.APIPath = "/apis"
	config.NegotiatedSerializer = serializer.DirectCodecFactory{CodecFactory: scheme.Codecs}
	config.UserAgent = rest.DefaultKubernetesUserAgent()

	restClient, err := rest.RESTClientFor(config)
	if err != nil {
		util.LogAndExit("Failed to connect to cluster, %s", err.Error())
	}

	result := kafakTopicList{}
	err = restClient.
		Get().
		Resource("kafkatopics").
		Namespace("lightbend").
		Do().
		Into(&result)

	if err != nil {
		util.LogAndExit(err.Error())
	}

	return result.Items
}

// RemoveKafkaTopics removes all Kafka topics created by Cloudflow in a namespace
func RemoveKafkaTopics(applicationId string) {

	config, err := clientcmd.BuildConfigFromFlags("", k8s.GetKubeConfig())
	if err != nil {
		util.LogErrorAndExit(err)
	}

	config.ContentConfig.GroupVersion = &schema.GroupVersion{Group: "kafka.strimzi.io", Version: "v1alpha1"}
	config.APIPath = "/apis"
	config.NegotiatedSerializer = serializer.DirectCodecFactory{CodecFactory: scheme.Codecs}
	config.UserAgent = rest.DefaultKubernetesUserAgent()

	restClient, err := rest.RESTClientFor(config)
	if err != nil {
		util.LogAndExit("Failed to connect to cluster, %s", err.Error())
	}

	kafkaTopics := GetAllKafkaTopics(applicationId)

	fmt.Println("Removing Kafka topics.")
	for _, topic := range kafkaTopics {
		labels := topic.GetLabels()
		if labels["app.kubernetes.io/part-of"] == applicationId && labels["app.kubernetes.io/managed-by"] == "cloudflow" {
			topicName := topic.GetObjectMeta().GetName()
			result := restClient.
				Delete().
				Resource("kafkatopics").
				Namespace("lightbend").
				Name(topicName).
				Do()

			if result.Error() != nil {
				util.LogErrorAndExit(err)
			}
		}
	}
}
