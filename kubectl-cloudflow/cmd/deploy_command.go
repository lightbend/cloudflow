package cmd

import (
	"errors"
	"fmt"
	"io/ioutil"
	"os"
	"os/exec"
	"reflect"
	"strings"

	"encoding/json"

	"github.com/docker/docker/client"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/cfapp"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/config"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/dockerclient"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/fileutil"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8sclient"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/printutil"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/scale"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/version"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/volume"
	"github.com/spf13/cobra"
	"golang.org/x/crypto/ssh/terminal"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

type deployOptions struct {
	cmd                     *cobra.Command
	username                string
	password                string
	passwordStdin           bool
	noRegistryCredentials   bool
	volumeMounts            []string
	replicasByStreamletName map[string]int
	configFiles             []string
}

func init() {

	deployOpts := &deployOptions{}
	deployOpts.cmd = &cobra.Command{
		Use:   "deploy",
		Short: "Deploys a Cloudflow application to the cluster.",
		Long: `Deploys a Cloudflow application to the cluster.

Configuration files in HOCON format can be passed through with the --conf flag. 
Configuration files are merged by concatenating the files passed with --conf flags. 
The last --conf [file] argument can override values specified in earlier --conf [file] arguments.
In the example below, where the same configuration path is used in file1.conf and file2.conf, 
the configuration value in file2.conf takes precedence, overriding the value provided by file1.conf:

kubectl cloudflow deploy swiss-knife.json --conf file1.conf --conf file2.conf

It is also possible to pass configuration values as command line arguments, as [config-key]=value pairs separated by
a space. The [config-key] must be an absolute path to the value, exactly how it would be defined in a config file. 
Some examples:

kubectl cloudflow deploy target/swiss-knife.json cloudflow.runtimes.spark.config.spark.driver.memoryOverhead=512
kubectl cloudflow deploy target/swiss-knife.json cloudflow.streamlets.spark-process.config-parameters.configurable-message='SPARK-OUTPUT:'


The arguments passed with '[config-key]=[value]' pairs take precedence over the files passed through with the '--conf' flag.

The command supports a flag --scale to specify the scale of each streamlet on deploy in the form of key/value
pairs ('streamlet-name=scale') separated by comma.
  kubectl-cloudflow deploy call-record-aggregator.json --scale cdr-aggregator=3,cdr-generator1=3

Streamlet volume mounts can be configured using the --volume-mount flag.
The flag accepts one or more key/value pair where the key is the name of the
volume mount, specified as '[streamlet-name].[volume-mount-name]', and the value
is the name of a Kubernetes Persistent Volume Claim, which needs to be located
in the same namespace as the Cloudflow application, e.g. the namespace with the
same name as the application.

  kubectl cloudflow deploy call-record-aggregator.json --volume-mount my-streamlet.mount=pvc-name

It is also possible to specify more than one "volume-mount" parameter.

  kubectl cloudflow deploy call-record-aggregator.json --volume-mount my-streamlet.mount=pvc-name --volume-mount my-other-streamlet.mount=pvc-name

You can optionally provide credentials for the docker registry that hosts the
images of the application by using the --username flag in combination with either
the --password-stdin or the --password flag. 

If no credentials are needed, for example, if the cluster already has credentials configured or if the registry does not require authentication to 
pull an image, use the '--no-registry-credentials' flag.

The --password-stdin flag is preferred because it is read from stdin, which
means that the password does not end up in the history of your shell.
One way to provide the password via stdin is to pipe it from a file:

  cat key.json | kubectl cloudflow deploy call-record-aggregator.json --username _json_key --password-stdin

You can also use --password, which is less secure:

  kubectl cloudflow deploy call-record-aggregator.json --username _json_key -password "$(cat key.json)"

If you do not provide a username and password, you will be prompted for them
the first time you deploy an image from a certain docker registry. The
credentials will be stored in a Kubernetes "image pull secret" and linked to
the Cloudflow service account. Subsequent usage of the deploy command will use
the stored credentials.

You can update the credentials with the "update-docker-credentials" command.
`,
		Example: `kubectl cloudflow deploy call-record-aggregator.json`,
		Args:    validateDeployCmdArgs,
		Run:     deployOpts.deployImpl,
	}
	deployOpts.cmd.Flags().StringVarP(&deployOpts.username, "username", "u", "", "docker registry username.")
	deployOpts.cmd.Flags().StringVarP(&deployOpts.password, "password", "p", "", "docker registry password.")
	deployOpts.cmd.Flags().BoolVarP(&deployOpts.passwordStdin, "password-stdin", "", false, "Take the password from stdin.")
	deployOpts.cmd.Flags().BoolVarP(&deployOpts.noRegistryCredentials, "no-registry-credentials", "", false, "Use this flag if the Kubernetes cluster already has credentials configured for the Docker registry where the Cloudflow application images are located, or if the registry is public and requires no authentication.")

	deployOpts.cmd.Flags().StringArrayVar(&deployOpts.volumeMounts, "volume-mount", []string{}, "Accepts a key/value pair separated by an equal sign. The key should be the name of the volume mount, specified as '[streamlet-name].[volume-mount-name]'. The value should be the name of an existing persistent volume claim.")
	deployOpts.cmd.Flags().StringToIntVar(&deployOpts.replicasByStreamletName, "scale", map[string]int{}, "Accepts key/value pairs for replicas per streamlet")
	deployOpts.cmd.Flags().StringArrayVar(&deployOpts.configFiles, "conf", []string{}, "Accepts one or more files in HOCON format.")

	rootCmd.AddCommand(deployOpts.cmd)
}

func (opts *deployOptions) deployImpl(cmd *cobra.Command, args []string) {
	version.FailOnProtocolVersionMismatch()

	applicationSpec, err := cfapp.LoadCloudflowApplicationSpec(args[0])
	if err != nil {
		printutil.LogAndExit("%s", err.Error())
	}

	validateStreamletRunnersDependencies(applicationSpec)

	namespace := applicationSpec.AppID

	k8sClient, client, appClient := getClientsOrExit(namespace)

	// TODO future: only create namespace if flag is provided to auto-create namespace.
	createNamespaceIfNotExist(k8sClient, applicationSpec)

	appInputSecret, err := config.HandleConfig(args, k8sClient, namespace, applicationSpec, opts.configFiles)
	if err != nil {
		printutil.LogErrorAndExit(err)
	}

	imageReference := getImageReferenceForDeployment(client, applicationSpec)

	// Extract volume mounts and update the application spec with the name of the PVC's
	applicationSpec, err = volume.ValidateVolumeMounts(k8sClient, applicationSpec, opts.volumeMounts)
	if err != nil {
		printutil.LogErrorAndExit(err)
	}

	if !opts.noRegistryCredentials {
		handleAuth(k8sClient, namespace, opts, imageReference)
	}

	applicationSpec, err = scale.UpdateReplicas(appClient, applicationSpec, opts.replicasByStreamletName)

	if err != nil {
		printutil.LogErrorAndExit(err)
	}

	ownerReference := createOrUpdateCloudflowApplication(appClient, applicationSpec)

	appInputSecret = config.UpdateSecretWithOwnerReference(ownerReference, appInputSecret)

	createOrUpdateAppInputSecret(k8sClient, namespace, appInputSecret)

	serviceAccount := newCloudflowServiceAccountWithImagePullSecrets(namespace)
	if _, err := createOrUpdateServiceAccount(k8sClient, namespace, serviceAccount, ownerReference); err != nil {
		printutil.LogAndExit("%s", err)
	}

	printutil.PrintSuccess("Deployment of application `%s` has started.\n", namespace)
}

// mutates opts.password with value from stdin if `--password-stdin` is set.
func verifyPasswordOptions(opts *deployOptions) error {
	if opts.password != "" {
		fmt.Println("WARNING! Using --password via the CLI is insecure. Use --password-stdin.")
		if opts.passwordStdin {
			return errors.New("--password and --password-stdin are mutually exclusive")
		}
	}

	if opts.passwordStdin {
		if opts.username == "" {
			return errors.New("Must provide --username with --password-stdin")
		}

		contents, err := ioutil.ReadAll(os.Stdin)
		if err != nil {
			return err
		}

		opts.password = strings.TrimSuffix(string(contents), "\n")
		opts.password = strings.TrimSuffix(opts.password, "\r")
	}
	return nil
}

func getClientsOrExit(namespace string) (*kubernetes.Clientset, *client.Client, *cfapp.CloudflowApplicationClient) {
	cloudflowApplicationClient, err := cfapp.GetCloudflowApplicationClient(namespace)
	if err != nil {
		printutil.LogAndExit("Failed to create a new client for Cloudflow application `%s`, %s", namespace, err.Error())
	}

	k8sClient, k8sErr := k8sclient.GetClient()
	if k8sErr != nil {
		printutil.LogAndExit("Failed to create a new kubernetes client for Cloudflow application `%s`, %s", namespace, k8sErr.Error())
	}

	client, err := dockerclient.GetClientForAPIVersionWithFallback()
	if err != nil {
		printutil.LogAndExit("Failed to create a new docker client (%s)", err.Error())
	}
	return k8sClient, client, cloudflowApplicationClient
}

// getImageReferenceForDeployment returns the image referenc for the image in the deployment
func getImageReferenceForDeployment(client *client.Client, applicationSpec cfapp.CloudflowApplicationSpec) *dockerclient.ImageReference {
	// Get the first available image, all images must be present in the same repository.
	image := applicationSpec.Deployments[0].Image

	imageReference, err := dockerclient.ParseImageReference(image)

	if err != nil {
		printutil.LogAndExit("Failed to parse the docker image reference '%s' while deploying the application,%s", image, err.Error())
	}

	return imageReference
}

// updateImageRefsWithDigests updates the imagesRefs to include the digest of the pulled images
func updateImageRefsWithDigests(spec cfapp.CloudflowApplicationSpec, pulledImages map[string]*dockerclient.PulledImage, dockerRegistryURL string, dockerRepository string) cfapp.CloudflowApplicationSpec {

	// replace tagged images with digest based names
	for i := range spec.Deployments {
		digest := pulledImages[spec.Deployments[i].Name].Digest

		var imageRef string
		if dockerRegistryURL == "" {
			imageRef = dockerRepository + "/" + digest
		} else {
			imageRef = dockerRegistryURL + "/" + dockerRepository + "/" + digest
		}

		spec.Deployments[i].Image = imageRef
	}
	return spec
}

func handleAuth(k8sClient *kubernetes.Clientset, namespace string, opts *deployOptions, imageReference *dockerclient.ImageReference) {
	dockerRegistryURL := imageReference.Registry
	if err := verifyPasswordOptions(opts); err == nil {
		if terminal.IsTerminal(int(os.Stdin.Fd())) && (opts.username == "" || opts.password == "") {
			if !dockerConfigEntryExists(k8sClient, namespace, dockerRegistryURL) {
				username, password := promptCredentials(dockerRegistryURL)
				createOrUpdateImagePullSecret(k8sClient, namespace, dockerRegistryURL, username, password)
			}
		} else if opts.username != "" && opts.password != "" {
			createOrUpdateImagePullSecret(k8sClient, namespace, dockerRegistryURL, opts.username, opts.password)
		} else {
			printutil.LogAndExit("Please provide username and password, by using both --username and --password-stdin, or, by using both --username and --password, or omit these flags to get prompted for username and password. If no authentication is needed, use the '--no-registry-credentials' flag to skip this step.")
		}
	} else {
		printutil.LogAndExit("%s", err)
	}
}

func getOwnerReference(cloudflowApplicationClient *cfapp.CloudflowApplicationClient, appID string) metav1.OwnerReference {
	// When the CR has been created, create a ownerReference using the uid from the stored CR and
	// then update secrets and service account with the ownerReference
	storedCR, err := cloudflowApplicationClient.Get(appID)
	if err != nil {
		printutil.LogAndExit("Failed to retrieve the application `%s`, %s", appID, err.Error())
	}
	return storedCR.GenerateOwnerReference()
}

func dockerConfigEntryExists(k8sClient *kubernetes.Clientset, namespace string, dockerRegistryURL string) bool {
	if serviceAccount, nserr := k8sClient.CoreV1().ServiceAccounts(namespace).Get(cloudflowAppServiceAccountName, metav1.GetOptions{}); nserr == nil {
		for _, secret := range serviceAccount.ImagePullSecrets {
			if secret, err := k8sClient.CoreV1().Secrets(namespace).Get(secret.Name, metav1.GetOptions{}); err == nil {
				var config dockerclient.ConfigJSON
				if err := json.Unmarshal(secret.Data[".dockerconfigjson"], &config); err == nil {
					_, exists := config.Auths[dockerRegistryURL]
					if exists == true {
						return exists
					}
				}

				var dockerConfig dockerclient.Config
				if err := json.Unmarshal(secret.Data[".dockercfg"], &dockerConfig); err == nil {
					_,
						exists := dockerConfig[dockerRegistryURL]
					if exists == true {
						return exists
					}
				}
			}
		}
	}
	return false
}

func createOrUpdateAppInputSecret(k8sClient *kubernetes.Clientset, namespace string, appInputSecret *corev1.Secret) {
	if _, err := k8sClient.CoreV1().Secrets(appInputSecret.Namespace).Get(appInputSecret.Name, metav1.GetOptions{}); err != nil {
		if _, err := k8sClient.CoreV1().Secrets(namespace).Create(appInputSecret); err != nil {
			printutil.LogAndExit("Failed to create secret: %s", err.Error())
		}
	} else {
		if _, err := k8sClient.CoreV1().Secrets(namespace).Update(appInputSecret); err != nil {
			printutil.LogAndExit("Failed to update secret: %s", err.Error())
		}
	}
}

func createNamespaceIfNotExist(k8sClient *kubernetes.Clientset, applicationSpec cfapp.CloudflowApplicationSpec) {
	ns := cfapp.NewCloudflowApplicationNamespace(applicationSpec, version.ReleaseTag, version.BuildNumber)
	if _, nserr := k8sClient.CoreV1().Namespaces().Get(ns.ObjectMeta.Name, metav1.GetOptions{}); nserr != nil {
		if _, nserr := k8sClient.CoreV1().Namespaces().Create(&ns); nserr != nil {
			printutil.LogAndExit("Failed to create namespace `%s`, %s", applicationSpec.AppID, nserr.Error())
		}
	}
}

func createOrUpdateCloudflowApplication(
	cloudflowApplicationClient *cfapp.CloudflowApplicationClient,
	spec cfapp.CloudflowApplicationSpec) metav1.OwnerReference {

	storedCR, errCR := cloudflowApplicationClient.Get(spec.AppID)

	if errCR == nil {
		cloudflowApplication := cfapp.UpdateCloudflowApplication(spec, *storedCR, version.ReleaseTag, version.BuildNumber)
		_, err := cloudflowApplicationClient.Update(cloudflowApplication)
		if err != nil {
			printutil.LogAndExit("Failed to update CloudflowApplication `%s`, %s", spec.AppID, err.Error())
		}
	} else if reflect.DeepEqual(*storedCR, cfapp.CloudflowApplication{}) {
		cloudflowApplication := cfapp.NewCloudflowApplication(spec, version.ReleaseTag, version.BuildNumber)

		_, err := cloudflowApplicationClient.Create(cloudflowApplication)
		if err != nil {
			printutil.LogAndExit("Failed to create CloudflowApplication `%s`, %s", spec.AppID, err.Error())
		}
	} else {
		if strings.Contains(errCR.Error(), "the server could not find the requested resource") {
			printutil.LogAndExit("Cannot create a Cloudflow application because the Cloudflow application custom resource defintion has not yet been installed on the cluster.")
		}
		printutil.LogAndExit("Failed to determine if Cloudflow application already have been created, %s", errCR.Error())
	}
	return getOwnerReference(cloudflowApplicationClient, spec.AppID)
}

func validateDeployCmdArgs(cmd *cobra.Command, args []string) error {

	if len(args) < 1 || args[0] == "" {
		return fmt.Errorf("please specify the full path to the file containing the application CR")
	}

	crFile := args[0]
	if !fileutil.FileExists(crFile) {
		return fmt.Errorf("File (%s) does not exist", crFile)
	}

	return nil
}

// The function validates that the operators for Spark and Flink are installed if the application uses any of those streamlet types
func validateStreamletRunnersDependencies(applicationSpec cfapp.CloudflowApplicationSpec) {

	type RunnerRequirements struct {
		crd     string
		version string
	}

	runnerTypes := make(map[string]RunnerRequirements)
	runnerTypes["spark"] = RunnerRequirements{"sparkapplications.sparkoperator.k8s.io", version.RequiredSparkVersion}
	runnerTypes["flink"] = RunnerRequirements{"flinkapplications.flink.k8s.io", version.RequiredFlinkVersion}

	validateRunnerType := func(crdName string, prettyName string, expectedVersion string) error {
		versionBytes, err := exec.Command("kubectl", "get", "crds", crdName, "-o", "jsonpath={$.spec.versions[*].name}").Output()
		versions := strings.Trim(string(versionBytes), " ")
		if err != nil {
			return fmt.Errorf("cannot detect that '%s' is installed, please install '%s' before continuing (%v)", prettyName, prettyName, err.Error())
		}
		versionsArray := strings.Split(versions, " ")
		for _, v := range versionsArray {
			if v == expectedVersion {
				return nil
			}
		}
		if len(versions) == 0 {
			return fmt.Errorf("cannot detect the installed version of the CRD '%s'", prettyName)
		}
		return fmt.Errorf("'%s' is installed but does not support the required version of the CRD, required '%s', installed '%s'", prettyName, expectedVersion, strings.Join(versionsArray, (",")))
	}

	runnersInApplicationSpec := make(map[string]bool)
	streamlets := applicationSpec.Streamlets
	for _, v := range streamlets {
		runtime := strings.ToLower(v.Descriptor.Runtime)
		if runtime != "akka" {
			runnersInApplicationSpec[runtime] = true
		}
	}

	var result []error
	for k, v := range runnerTypes {
		if runnersInApplicationSpec[k] == true {
			if err := validateRunnerType(v.crd, strings.Title(k), v.version); err != nil {
				result = append(result, err)
			}
		}
	}

	if len(result) != 0 {
		for _, err := range result {
			printutil.PrintError("%s", err.Error())
		}
		os.Exit(1)
	}

}
