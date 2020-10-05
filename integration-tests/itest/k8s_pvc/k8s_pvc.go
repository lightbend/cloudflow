package k8s_pvc

import (
	"context"
	"fmt"
	"github.com/ghodss/yaml"
	"io/ioutil"

	coreV1 "k8s.io/api/core/v1"
	metaV1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"

	_ "k8s.io/client-go/plugin/pkg/client/auth"
)

func CreatePVC(path string, namespace string, clientset *kubernetes.Clientset) (*coreV1.PersistentVolumeClaim, error) {

	pvcClient := clientset.CoreV1().PersistentVolumeClaims(namespace)

	bytes, err := ioutil.ReadFile(path)
	if err != nil {
		panic(err.Error())
	}

	var pvcSpec coreV1.PersistentVolumeClaim
	err = yaml.Unmarshal(bytes, &pvcSpec)
	if err != nil {
		panic(err.Error())
	}

	secret, err := pvcClient.Create(context.TODO(), &pvcSpec, metaV1.CreateOptions{})
	if err != nil {
		panic(err)
	}
	fmt.Printf("Created PVC %s\n", pvcSpec.ObjectMeta.Name)
	return secret, err
}

func DeletePVC(secretName string, namespace string, clientset *kubernetes.Clientset) error {
	pvcClient := clientset.CoreV1().PersistentVolumeClaims(namespace)
	return pvcClient.Delete(context.TODO(), secretName, metaV1.DeleteOptions{})
}

func DeletePVCs(namespace string, clientset *kubernetes.Clientset) error {
	pvcs, err := GetPVCs(namespace, clientset)
	if err != nil {
		return err
	}
	for _, sec := range pvcs.Items {
		fmt.Println("deleting %s", sec.ObjectMeta.Name)
		err := DeletePVC(sec.ObjectMeta.Name, namespace, clientset)
		if err != nil {
			return err
		}
	}
	return nil
}

func GetPVCs(namespace string, clientset *kubernetes.Clientset) (*coreV1.PersistentVolumeClaimList, error) {
	pvcClient := clientset.CoreV1().PersistentVolumeClaims(namespace)
	return pvcClient.List(context.TODO(), metaV1.ListOptions{})
}
