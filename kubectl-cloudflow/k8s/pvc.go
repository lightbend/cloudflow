package k8s

import (
	"fmt"
	"time"

	"k8s.io/apimachinery/pkg/watch"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods

	v1 "k8s.io/api/core/v1"

	"github.com/gosuri/uilive"
)

// GetAllPersistentVolumeClaims returns all PVCs manged by Cloudflow for a specific application
func GetAllPersistentVolumeClaims(applicationId string) []v1.PersistentVolumeClaim {
	clientset, err := GetClient()
	if err != nil {
		util.LogAndExit("Failed to connect to cluster, %s", err.Error())
	}
	api := clientset.CoreV1()
	pvcs, err := api.PersistentVolumeClaims(applicationId).List(metav1.ListOptions{})
	if err != nil {
		util.LogAndExit("Failed to connect to cluster, %s", err.Error())
	}
	return pvcs.Items
}

// RemovePersistentVolumeClaims removes all PVCs created by Cloudflow in a namespace
func RemovePersistentVolumeClaims(applicationId string) {

	pvcs := GetAllPersistentVolumeClaims(applicationId)

	clientset, err := GetClient()
	if err != nil {
		util.LogAndExit("Failed to connect to cluster, %s", err.Error())
	}
	api := clientset.CoreV1()

	watcher, watchError := api.PersistentVolumeClaims(applicationId).Watch(metav1.ListOptions{})
	if watchError != nil {
		util.LogErrorAndExit(watchError)
	}

	totalPVCcount := 0
	for _, pvc := range pvcs {
		if pvc.Labels["app.kubernetes.io/managed-by"] == "cloudflow" {

			deletionErr := api.PersistentVolumeClaims(applicationId).Delete(pvc.GetName(), &metav1.DeleteOptions{})
			if deletionErr != nil {
				util.LogAndExit("Failed to delete PVC %s from cluster, %s", pvc.GetName(), deletionErr.Error())
			}

			totalPVCcount = totalPVCcount + 1
		}
	}
	if totalPVCcount == 0 {
		return
	}

	fmt.Println("Removing PVCs...")

	ch := watcher.ResultChan()

	writer := uilive.New()
	progress := []string{"-", "\\", "|", "/", "-", "\\", "|", "/"}
	writer.Start()
	currentPos := 0
	for {
		select {
		case event := <-ch:
			switch event.Type {
			case watch.Deleted:
				pvc, _ := event.Object.(*v1.PersistentVolumeClaim)
				fmt.Fprintf(writer.Bypass(), "- PVC `%s` removed.\n", pvc.GetName())
				totalPVCcount = totalPVCcount - 1
				if totalPVCcount == 0 {
					writer.Stop()
					return
				}
			}
		default:
			fmt.Fprintf(writer, "%s\n", progress[currentPos])
			currentPos = currentPos + 1
			if currentPos >= len(progress) {
				currentPos = 0
			}
			time.Sleep(time.Millisecond * 100)
		}
	}
}
