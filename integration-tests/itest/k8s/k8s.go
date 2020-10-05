package k8s

import (
	"fmt"
	"context"
	"strings"

	metaV1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"os/exec"

	_ "k8s.io/client-go/plugin/pkg/client/auth"
)

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
	return "Not matching pods with that file mounted", nil
}

func CopyFile(namespace string, clientset *kubernetes.Clientset, podPartialName string, localSrcFile string, podDestFile string) error {

	coreV1Client := clientset.CoreV1()
	pods, err := coreV1Client.Pods(namespace).List(context.TODO(), metaV1.ListOptions{})
	if err != nil {
		return err
	}
	for _, pod := range pods.Items {
		if strings.Contains(pod.Name, podPartialName) {
			cmd := exec.Command("kubectl", "cp", localSrcFile, fmt.Sprintf("%s/%s:%s", namespace, pod.Name, podDestFile))
			fmt.Println("Writing %s",cmd.String())
			out, err := cmd.CombinedOutput()
			if err != nil {
    			fmt.Println(fmt.Sprint(err) + ": " + string(out))
    			return err
			}
			return nil
		}
	}
	return nil 
}