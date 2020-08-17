package cmd

import (
	"bufio"
	"fmt"
	"os"
	"strings"
	"syscall"

	corev1 "k8s.io/api/core/v1"
	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cfapp"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/dockerclient"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8sclient"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/printutil"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/version"

	"github.com/spf13/cobra"
	"golang.org/x/crypto/ssh/terminal"
	"k8s.io/client-go/kubernetes"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods

	"encoding/base64"
	"encoding/json"
)

type updateDockerCredentialsOptions struct {
	cmd *cobra.Command
}

const imagePullSecretName = "cloudflow-image-pull-secret"
const cloudflowAppServiceAccountName = "cloudflow-app-serviceaccount"

func init() {

	updateDockerCredentialsOpts := &updateDockerCredentialsOptions{}
	updateDockerCredentialsOpts.cmd = &cobra.Command{
		Use:   "update-docker-credentials",
		Short: "Updates docker registry credentials that are used to pull Cloudflow application images.",
		Long: `This command configures the cloudflow service account to use the specified Docker registry credentials so that 
Cloudflow application images can be pulled from that docker registry.
The arguments to the command consists of the namespace that the application is deployed to 
and the docker registry that you want cloudflow to pull Cloudflow application images from. You will be prompted for a username and password.`,

		Example: `kubectl cloudflow update-docker-credentials my-app docker-registry-default.server.example.com`,

		Run:  updateDockerCredentialsOpts.updateDockerCredentialsImpl,
		Args: validateAddDockerRegistryCredentialsCMDArgs,
	}
	rootCmd.AddCommand(updateDockerCredentialsOpts.cmd)
}

func (c *updateDockerCredentialsOptions) updateDockerCredentialsImpl(cmd *cobra.Command, args []string) {
	version.FailOnProtocolVersionMismatch()

	appID := args[0]
	dockerRegistryURL := args[1]

	username, password := promptCredentials(dockerRegistryURL)

	k8sClient, k8sErr := k8sclient.GetClient()
	if k8sErr != nil {
		printutil.LogAndExit("Failed to create new kubernetes client, %s", k8sErr.Error())
	}

	createOrUpdateNamespace(k8sClient, appID)
	createOrUpdateImagePullSecret(k8sClient, appID, dockerRegistryURL, username, password)

	fmt.Printf("Image pull secret is added to namespace %s to pull from %s\n", appID, dockerRegistryURL)
}

func validateAddDockerRegistryCredentialsCMDArgs(cmd *cobra.Command, args []string) error {

	if len(args) != 2 {
		return fmt.Errorf("you need to specify the namespace the Cloudflow application is going to be deployed in and the docker registry URL to add credentials for")
	}

	return nil
}

func promptCredentials(dockerRegistryURL string) (string, string) {
	if len(dockerRegistryURL) != 0 {
		fmt.Printf("Please provide credentials for docker registry '%s'.\n", dockerRegistryURL)
	} else {
		fmt.Printf("Please provide credentials for the 'Docker Hub' account.\n")
	}
	fmt.Println("The credentials will be stored in a Kubernetes image pull secret, so that the application can successfully be deployed.")
	reader := bufio.NewReader(os.Stdin)

	fmt.Print("Username: ")
	username, _ := reader.ReadString('\n')

	fmt.Print("Password: ")
	bytePassword, _ := terminal.ReadPassword(int(syscall.Stdin))
	password := string(bytePassword)
	fmt.Print("\n")

	return strings.TrimSpace(username), strings.TrimSpace(password)
}

func createOrUpdateNamespace(k8sClient *kubernetes.Clientset, appID string) {
	ns := newNamespace(appID)
	if _, nserr := k8sClient.CoreV1().Namespaces().Get(ns.ObjectMeta.Name, metav1.GetOptions{}); nserr != nil {
		if _, nserr := k8sClient.CoreV1().Namespaces().Create(&ns); nserr != nil {
			printutil.LogAndExit("Failed to create namespace `%s`, %s", appID, nserr.Error())
		}
	}
}

func newNamespace(appID string) v1.Namespace {
	return v1.Namespace{
		ObjectMeta: metav1.ObjectMeta{
			Name:   appID,
			Labels: cfapp.CreateLabels(appID),
		},
	}
}

func updateServiceAccountWithImagePullSecret(k8sClient *kubernetes.Clientset, appID string, serviceAccountName string) {
	if serviceAccount, nserr := k8sClient.CoreV1().ServiceAccounts(appID).Get(serviceAccountName, metav1.GetOptions{}); nserr == nil {

		serviceAccount = appendCloudflowImagePullSecretName(serviceAccount, imagePullSecretName)
		if _, err := k8sClient.CoreV1().ServiceAccounts(appID).Update(serviceAccount); err != nil {
			printutil.LogAndExit("Failed to update the default service account in %s with the Cloudflow image pull secret, this will result in Spark executor pods failing to run, %s", appID, err.Error())
		}
	} else {
		printutil.LogAndExit("The default service account has not yet been created in `%s`, please re-run the command again.", appID)
	}
}

func appendCloudflowImagePullSecretName(serviceAccount *v1.ServiceAccount, newImagePullSecretName string) *v1.ServiceAccount {
	secretRef := v1.LocalObjectReference{
		Name: newImagePullSecretName,
	}
	cloudflowImagePullSecretFound := false
	for _, secret := range serviceAccount.ImagePullSecrets {
		if secret.Name == newImagePullSecretName {
			cloudflowImagePullSecretFound = true
			break
		}
	}
	if cloudflowImagePullSecretFound == false {
		serviceAccount.ImagePullSecrets = append(serviceAccount.ImagePullSecrets, secretRef)
	}
	return serviceAccount
}

func createOrUpdateServiceAccount(
	k8sClient *kubernetes.Clientset,
	appID string,
	serviceAccount v1.ServiceAccount,
	ownerReference metav1.OwnerReference) (*v1.ServiceAccount, error) {

	serviceAccount.OwnerReferences = []metav1.OwnerReference{ownerReference}

	if _, nserr := k8sClient.CoreV1().ServiceAccounts(appID).Get(serviceAccount.ObjectMeta.Name, metav1.GetOptions{}); nserr != nil {
		if _, nserr := k8sClient.CoreV1().ServiceAccounts(appID).Create(&serviceAccount); nserr != nil {
			printutil.LogAndExit("Failed to create Cloudflow app service account in `%s`, %s", appID, nserr.Error())
		}
	} else {
		if _, err := k8sClient.CoreV1().ServiceAccounts(appID).Update(&serviceAccount); err != nil {
			printutil.LogAndExit("Failed to update Cloudflow app service account in `%s`, %s", appID, err.Error())
		}
	}
	var err error
	if updated, err := k8sClient.CoreV1().ServiceAccounts(appID).Update(&serviceAccount); err == nil {
		return updated, nil
	}
	return nil, fmt.Errorf("failed to update Cloudflow app service account in `%s`, %s", appID, err)
}

func newCloudflowServiceAccount(appID string) v1.ServiceAccount {
	return v1.ServiceAccount{
		ObjectMeta: metav1.ObjectMeta{
			Name:      cloudflowAppServiceAccountName,
			Namespace: appID,
			Labels:    cfapp.CreateLabels(appID),
		},
	}
}

func newCloudflowServiceAccountWithImagePullSecrets(appID string) v1.ServiceAccount {
	secretRef := v1.LocalObjectReference{
		Name: imagePullSecretName,
	}
	imagePullSecrets := []v1.LocalObjectReference{secretRef}
	serviceAccount := newCloudflowServiceAccount(appID)
	serviceAccount.ImagePullSecrets = imagePullSecrets
	auto := true
	serviceAccount.AutomountServiceAccountToken = &auto
	return serviceAccount
}

func createOrUpdateImagePullSecret(k8sClient *kubernetes.Clientset, appID string, dockerRegistryURL string, username string, password string) {
	secret := &corev1.Secret{
		Type: corev1.SecretTypeDockerConfigJson,
		ObjectMeta: metav1.ObjectMeta{
			Name:      imagePullSecretName,
			Namespace: appID,
			Labels:    cfapp.CreateLabels(appID),
		},
	}

	configEntry := dockerclient.ConfigEntry{
		Username: username,
		Password: password,
		Auth:     base64.URLEncoding.EncodeToString([]byte(fmt.Sprintf("%s:%s", username, password))),
	}

	newConfigJSON := dockerclient.ConfigJSON{
		Auths: map[string]dockerclient.ConfigEntry{
			dockerRegistryURL: configEntry,
		},
	}

	encodedJSON, err := json.Marshal(newConfigJSON)

	if err != nil {
		printutil.LogAndExit("Failed to create docker config json, %s", err.Error())
	}

	secret.Data = make(map[string][]byte)
	secret.Data[".dockerconfigjson"] = []byte(encodedJSON)

	if imagePullSecret, err := k8sClient.CoreV1().Secrets(secret.ObjectMeta.Namespace).Get(secret.ObjectMeta.Name, metav1.GetOptions{}); err != nil {
		if _, err := k8sClient.CoreV1().Secrets(appID).Create(secret); err != nil {
			printutil.LogAndExit("Failed to create image pull secret, %s", err.Error())
		}
	} else {
		updateConfigJSON := dockerclient.ConfigJSON{}

		if err := json.Unmarshal(imagePullSecret.Data[".dockerconfigjson"], &updateConfigJSON); err != nil {
			printutil.LogAndExit("Failed to read docker config json from image pull secret %s, %s", imagePullSecretName, err.Error())
		}

		updateConfigJSON.Auths[dockerRegistryURL] = configEntry

		updatedJSON, err := json.Marshal(updateConfigJSON)

		if err != nil {
			printutil.LogAndExit("Failed to update docker config json, %s", err.Error())
		}

		imagePullSecret.Data[".dockerconfigjson"] = []byte(updatedJSON)

		if _, err := k8sClient.CoreV1().Secrets(appID).Update(imagePullSecret); err != nil {
			printutil.LogAndExit("Failed to update image pull secret, %s", err.Error())
		}
	}
}
