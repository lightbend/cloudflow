package k8s

import (
	"fmt"
	"context"
	"strings"

	metaV1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"os/exec"
	"k8s.io/client-go/tools/clientcmd"
	"os"


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

func ReadFile(namespace string, clientset *kubernetes.Clientset, podPartialName string, readFilePath string) (string, error) {

	coreV1Client := clientset.CoreV1()
	pods, err := coreV1Client.Pods(namespace).List(context.TODO(), metaV1.ListOptions{})
	if err != nil {
		panic(err.Error())
	}
	for _, pod := range pods.Items {
		if strings.Contains(pod.Name, podPartialName) {
			cmd := exec.Command("kubectl", "exec", pod.Name, "-n", namespace, "--", "cat", readFilePath)
			out, err := cmd.CombinedOutput()
			if err != nil {
    			fmt.Println(fmt.Sprint(err) + ": " + string(out))
			}
			return strings.TrimSuffix(string(out),"\n"), err
		}
	}
	return "", fmt.Errorf("not matching pods with name containing '%s'",podPartialName)
}

func CopyLocalFileToMatchingPod(namespace string, clientset *kubernetes.Clientset, podPartialName string, localSrcFile string, podDestFile string) error {

	coreV1Client := clientset.CoreV1()
	pods, err := coreV1Client.Pods(namespace).List(context.TODO(), metaV1.ListOptions{})
	if err != nil {
		return err
	}
	for _, pod := range pods.Items {
		if strings.Contains(pod.Name, podPartialName) {
			cmd := exec.Command("kubectl", "cp", localSrcFile, fmt.Sprintf("%s/%s:%s", namespace, pod.Name, podDestFile))
			out, err := cmd.CombinedOutput()
			if err != nil {
    			fmt.Println(fmt.Sprint(err) + ": " + string(out))
    			return err
			}
			return nil
		}
	}
	return fmt.Errorf("nothing has been copied") 
}
