package k8sclient

import (
	"os"
	"path/filepath"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/printutil"
	"k8s.io/client-go/kubernetes"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
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
		printutil.LogErrorAndExit(err)
	}

	return kubernetes.NewForConfig(config)
}
