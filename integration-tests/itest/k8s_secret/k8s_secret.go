package k8s_secret

import (
	"context"
	"fmt"
	"github.com/ghodss/yaml"
	"io/ioutil"
	"strings"

	coreV1 "k8s.io/api/core/v1"
	metaV1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
	"os"
	"os/exec"

	_ "k8s.io/client-go/plugin/pkg/client/auth"
)

func InitClient() *kubernetes.Clientset {
	kubeconfig := os.Getenv("HOME") + "/.kube/config"
	config, err := clientcmd.BuildConfigFromFlags("", kubeconfig)
	if err != nil {
		panic(err.Error())
	}
	clientset, err := kubernetes.NewForConfig(config)
	if err != nil {
		panic(err.Error())
	}
	return clientset
}

func CreateSecret(path string, namespace string, clientset *kubernetes.Clientset) (*coreV1.Secret, error) {

	secretsClient := clientset.CoreV1().Secrets(namespace)

	bytes, err := ioutil.ReadFile(path)
	if err != nil {
		panic(err.Error())
	}

	var secretSpec coreV1.Secret
	err = yaml.Unmarshal(bytes, &secretSpec)
	if err != nil {
		panic(err.Error())
	}

	secret, err := secretsClient.Create(context.TODO(), &secretSpec, metaV1.CreateOptions{})
	if err != nil {
		panic(err)
	}
	fmt.Printf("Created secret %s\n", secretSpec.ObjectMeta.Name)
	return secret, err
}

func DeleteSecret(secretName string, namespace string, clientset *kubernetes.Clientset) error {

	secretsClient := clientset.CoreV1().Secrets(namespace)
	return secretsClient.Delete(context.TODO(), secretName, metaV1.DeleteOptions{})
}

func DeleteSecrets(namespace string, clientset *kubernetes.Clientset) error {
	secrets, err := GetSecrets(namespace, clientset)
	if err != nil {
		return err
	}
	for _, sec := range secrets.Items {
		fmt.Println("deleting %s", sec.ObjectMeta.Name)
		err := DeleteSecret(sec.ObjectMeta.Name, namespace, clientset)
		if err != nil {
			return err
		}
	}
	return nil
}

func GetSecrets(namespace string, clientset *kubernetes.Clientset) (*coreV1.SecretList, error) {
	secretsClient := clientset.CoreV1().Secrets(namespace)
	return secretsClient.List(context.TODO(), metaV1.ListOptions{})
}

func ReadMountedSecret(namespace string, clientset *kubernetes.Clientset, podPartialName string, readFilePath string) (string, error) {

	coreV1Client := clientset.CoreV1()
	pods, err := coreV1Client.Pods(namespace).List(context.TODO(), metaV1.ListOptions{})
	if err != nil {
		panic(err.Error())
	}
	for _, pod := range pods.Items {
		if strings.Contains(pod.Name, podPartialName) {
			cmd := exec.Command("kubectl", "exec", pod.Name, "-n", namespace, "--", "cat", readFilePath)
			out, err := cmd.CombinedOutput()
			return string(out), err
		}
	}
	return "Not matching pods with that file mounted", nil
}
