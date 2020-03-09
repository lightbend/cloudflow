package cmd

import (
	"errors"
	"fmt"
	"io/ioutil"
	"os"
	"reflect"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/deploy"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/docker"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/fileutils"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/scale"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/verify"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/version"
	"github.com/spf13/cobra"
	"golang.org/x/crypto/ssh/terminal"

	"encoding/json"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

type deployOptions struct {
	cmd                     *cobra.Command
	blueprintFilePath       string
	imagePath               string
	username                string
	password                string
	passwordStdin           bool
	volumeMounts            []string
	replicasByStreamletName map[string]int
}

func init() {

	deployOpts := &deployOptions{}
	deployOpts.cmd = &cobra.Command{
		Use:   "deploy",
		Short: "Deploys a Cloudflow application to the cluster.",
		Long: `Deploys a Cloudflow application to the cluster.
The behavior of the command depends upon the flags passed in to it. The command supports deployment of
a cloudflow application either through a blueprint file or through a docker image.

Deployment through a blueprint file can be done using the --blueprint flag followed by the
blueprint file name.

	kubectl cloudflow deploy --blueprint ./call-record-aggregator-blueprint.conf

Deployment through a docker image can be done using the --image flag followed by the image
url.

	kubectl cloudflow deploy --image docker.registry.com/my-company/sensor-data-scala:292-c183d80 

The command optionally takes configuration parameters as arguments specified as key=value pairs in the form of
'[streamlet-name].[configuration-parameter]=[value]', separated by a space.

	kubectl cloudflow deploy --image registry.test-cluster.io/cloudflow/sensor-data-scala:292-c183d80 valid-logger.log-level=info valid-logger.msg-prefix=valid

The command supports a flag --scale to specify the scale of each streamlet on deploy in the form of key/value
pairs ('streamlet-name=scale') separated by comma.

  kubectl-cloudflow deploy --blueprint call-record-aggregator-new-blueprint.conf --scale cdr-aggregator=3,cdr-generator1=3

Streamlet volume mounts can be configured using the --volume-mount flag.
The flag accepts one or more key/value pair where the key is the name of the
volume mount, specified as '[streamlet-name].[volume-mount-name]', and the value
is the name of a Kubernetes Persistent Volume Claim, which needs to be located
in the same namespace as the Cloudflow application, e.g. the namespace with the
same name as the application.

	kubectl cloudflow deploy --image docker.registry.com/my-company/sensor-data-scala:292-c183d80 --volume-mount my-streamlet.mount=pvc-name
  kubectl cloudflow deploy --blueprint examples/call-record-aggregator/call-record-pipeline/src/main/blueprint/blueprint.conf --volume-mount my-streamlet.mount=pvc-name

It is also possible to specify more than one "volume-mount" parameter.

  kubectl cloudflow deploy docker.registry.com/my-company/sensor-data-scala:292-c183d80 --volume-mount my-streamlet.mount=pvc-name --volume-mount my-other-streamlet.mount=pvc-name

You can optionally provide credentials for the docker registry that hosts the
specified image by using the --username flag in combination with either
the --password-stdin or the --password flag.

The --password-stdin flag is preferred because it is read from stdin, which
means that the password does not end up in the history of your shell.
One way to provide the password via stdin is to pipe it from a file:

  cat key.json | kubectl cloudflow deploy docker.registry.com/my-company/sensor-data-scala:292-c183d80 --username _json_key --password-stdin

You can also use --password, which is less secure:

  kubectl cloudflow deploy docker.registry.com/my-company/sensor-data-scala:292-c183d80 --username _json_key -password "$(cat key.json)"

If you do not provide a username and password, you will be prompted for them
the first time you deploy an image from a certain docker registry. The
credentials will be stored in a Kubernetes "image pull secret" and linked to
the Cloudflow service account. Subsequent usage of the deploy command will use
the stored credentials.

You can update the credentials with the "update-docker-credentials" command.
`,
		Example: `kubectl cloudflow deploy -i registry.test-cluster.io/cloudflow/sensor-data-scala:292-c183d80 valid-logger.log-level=info valid-logger.msg-prefix=valid`,
		PreRun: func(cmd *cobra.Command, args []string) {
			if cmd.Flag("image").Changed == false && cmd.Flag("blueprint").Changed == false {
				util.LogAndExit("Please specify either the blueprint file or the image path")
			}
			if (cmd.Flag("image").Changed == true) && (cmd.Flag("blueprint").Changed == true) {
				util.LogAndExit("%s", "Cannot specify both blueprint and image in command")
			}
			if cmd.Flag("image").Changed == true {
				fmt.Printf("Deploying image %s\n", cmd.Flag("image").Value.String())
			} else if cmd.Flag("blueprint").Changed == true {
				fmt.Printf("Deploying blueprint %s\n", cmd.Flag("blueprint").Value.String())
			}
		},
		Run: deployOpts.deployImpl,
	}
	deployOpts.cmd.Flags().StringVarP(&deployOpts.blueprintFilePath, "blueprint", "b", "", "blueprint file path.")
	deployOpts.cmd.Flags().StringVarP(&deployOpts.imagePath, "image", "i", "", "docker image path.")
	deployOpts.cmd.Flags().StringVarP(&deployOpts.username, "username", "u", "", "docker registry username.")
	deployOpts.cmd.Flags().StringVarP(&deployOpts.password, "password", "p", "", "docker registry password.")
	deployOpts.cmd.Flags().BoolVarP(&deployOpts.passwordStdin, "password-stdin", "", false, "Take the password from stdin")
	deployOpts.cmd.Flags().StringArrayVar(&deployOpts.volumeMounts, "volume-mount", []string{}, "Accepts a key/value pair separated by an equal sign. The key should be the name of the volume mount, specified as '[streamlet-name].[volume-mount-name]'. The value should be the name of an existing persistent volume claim.")
	deployOpts.cmd.Flags().StringToIntVar(&deployOpts.replicasByStreamletName, "scale", map[string]int{}, "Accepts key/value pairs for replicas per streamlet")

	rootCmd.AddCommand(deployOpts.cmd)
}

func (opts *deployOptions) deployImpl(cmd *cobra.Command, args []string) {
	version.FailOnProtocolVersionMismatch()

	if cmd.Flag("image").Changed == true {
		deployImage(cmd, opts, args)
	} else {
		deployBlueprint(cmd, opts, args)
	}
}

func deployBlueprint(cmd *cobra.Command, opts *deployOptions, args []string) {
	// read blueprint file and load its content
	blueprintFile := cmd.Flag("blueprint").Value.String()
	contents, err := fileutils.GetFileContents(blueprintFile)
	contents = strings.TrimSpace(contents)
	if err != nil {
		util.LogAndExit("Failed to fetch blueprint contents from %s", blueprintFile)
	}

	blueprint, err := verify.VerifyBlueprint(contents)
	if err != nil {
		util.LogAndExit("Blueprint verification failed. Error: %s", err.Error())
	}

	util.PrintSuccess("Blueprint verified for application %s .. Proceeding with deployment\n", blueprint.GetName())

	applicationSpec, pulledImages, err := deploy.CreateApplicationSpecFromBlueprintAndImages(blueprint, opts.replicasByStreamletName)
	if err != nil {
		util.LogAndExit("Failed to create cloudflow application spec from blueprint and images: %s", err.Error())
	}

	namespace := applicationSpec.AppID
	k8sClient, k8sErr := k8s.GetClient()
	if k8sErr != nil {
		util.LogAndExit("Failed to connect to kubernetes cluster %s", k8sErr.Error())
	}

	cloudflowApplicationClient, err := k8s.GetCloudflowApplicationClient(namespace)
	if err != nil {
		util.LogAndExit("Failed to create new kubernetes client with cloudflow config `%s`, %s", namespace, err.Error())
	}

	// Extract volume mounts and update the application spec with the name of the PVC's
	applicationSpec, err = deploy.ValidateVolumeMounts(k8sClient, applicationSpec, opts.volumeMounts)
	if err != nil {
		util.LogErrorAndExit(err)
	}

	configurationParameters := deploy.SplitConfigurationParameters(args[0:])
	configurationParameters = deploy.AppendExistingValuesNotConfigured(k8sClient, applicationSpec, configurationParameters)
	configurationParameters = deploy.AppendDefaultValuesForMissingConfigurationValues(applicationSpec, configurationParameters)
	configurationKeyValues, validationError := deploy.ValidateConfigurationAgainstDescriptor(applicationSpec, configurationParameters)

	if validationError != nil {
		util.LogAndExit("%s", validationError.Error())
	}
	createNamespaceIfNotExist(k8sClient, applicationSpec)
	dockerRegistryURL := blueprint.GetDockerRegistryURL()

	// this needs to change when we have multiple registries in images section of the blueprint
	for _, pulledImage := range pulledImages {
		if pulledImage.Authenticated {
			if err := verifyPasswordOptions(opts); err == nil {
				if terminal.IsTerminal(int(os.Stdin.Fd())) && (opts.username == "" || opts.password == "") {
					if !dockerConfigEntryExists(k8sClient, namespace, dockerRegistryURL) {
						username, password := promptCredentials(dockerRegistryURL)
						createOrUpdateImagePullSecret(k8sClient, namespace, dockerRegistryURL, username, password)
					}
				} else if opts.username != "" && opts.password != "" {
					createOrUpdateImagePullSecret(k8sClient, namespace, dockerRegistryURL, opts.username, opts.password)
				} else {
					util.LogAndExit("Please provide username and password, by using both --username and --password-stdin, or, by using both --username and --password, or omit these flags to get prompted for username and password.")
				}
			} else {
				util.LogAndExit("%s", err)
			}
		}
	}

	// Get the Cloudflow operator ownerReference
	ownerReference := version.GetOwnerReferenceForCloudflowOperator()

	serviceAccount := newCloudflowServiceAccountWithImagePullSecrets(namespace, ownerReference)

	if _, err := createOrUpdateServiceAccount(k8sClient, namespace, serviceAccount); err != nil {
		util.LogAndExit("%s", err)
	}

	// Delay the creation of the secret for after the ownerReferences has been added.
	// Creating then updating the secret generates problem for the Flink streamlets deployment.
	streamletNameSecretMap := deploy.CreateSecretsData(&applicationSpec, configurationKeyValues)
	createOrUpdateStreamletSecrets(k8sClient, namespace, streamletNameSecretMap)

	applicationSpec, err = copyReplicaConfigurationFromCurrentApplication(cloudflowApplicationClient, applicationSpec)
	if err != nil {
		util.LogAndExit("The application descriptor is invalid, %s", err.Error())
	}

	createOrUpdateCloudflowApplication(cloudflowApplicationClient, applicationSpec, ownerReference)

	// When the CR has been created, create a ownerReference using the uid from the stored CR and
	// then update secrets and service account with the ownerReference
	storedCR, err := cloudflowApplicationClient.Get(applicationSpec.AppID)
	if err != nil {
		util.LogAndExit("Failed to retrieve the application `%s`, %s", applicationSpec.AppID, err.Error())
	}
	ownerReference = storedCR.GenerateOwnerReference()

	streamletNameSecretMap = deploy.UpdateSecretsWithOwnerReference(ownerReference, streamletNameSecretMap)
	createOrUpdateStreamletSecrets(k8sClient, namespace, streamletNameSecretMap)

	serviceAccount.ObjectMeta.OwnerReferences = []metav1.OwnerReference{ownerReference}
	if _, err := createOrUpdateServiceAccount(k8sClient, namespace, serviceAccount); err != nil {
		util.LogAndExit("%s", err)
	}

	util.PrintSuccess("Deployment of application `%s` has started.\n", namespace)
}

func deployImage(cmd *cobra.Command, opts *deployOptions, args []string) {
	imageRef := cmd.Flag("image").Value.String()
	fmt.Printf("Image Ref: %s\n", imageRef)

	imageReference, err := verify.ParseImageReference(imageRef)

	if err != nil {
		util.LogAndExit("%s", err.Error())
	}

	dockerRegistryURL := imageReference.Registry
	dockerRepository := imageReference.Repository
	applicationSpec, pulledImage, err := deploy.GetCloudflowApplicationDescriptorFromDockerImage(dockerRegistryURL, dockerRepository, imageRef)
	if err != nil {
		util.LogAndExit("Error getting application descriptor from docker image: %s", err.Error())
	}

	// update deployment with replicas passed through command line
	for _, deployment := range applicationSpec.Deployments {
		if s, ok := opts.replicasByStreamletName[deployment.StreamletName]; ok {
			if spec, err := scale.UpdateDeploymentWithReplicas(applicationSpec, deployment.StreamletName, s); err != nil {
				util.LogAndExit("Cannot set replicas for streamlet [%s] %s", deployment.StreamletName, err.Error())
			} else {
				applicationSpec = spec
			}
		}
	}

	namespace := applicationSpec.AppID

	cloudflowApplicationClient, err := k8s.GetCloudflowApplicationClient(namespace)
	if err != nil {
		util.LogAndExit("Failed to create new client for Cloudflow application `%s`, %s", namespace, err.Error())
	}

	k8sClient, k8sErr := k8s.GetClient()
	if k8sErr != nil {
		util.LogAndExit("Failed to create new kubernetes client for Cloudflow application `%s`, %s", namespace, k8sErr.Error())
	}

	// Extract volume mounts and update the application spec with the name of the PVC's
	applicationSpec, err = deploy.ValidateVolumeMounts(k8sClient, applicationSpec, opts.volumeMounts)
	if err != nil {
		util.LogErrorAndExit(err)
	}

	configurationParameters := deploy.SplitConfigurationParameters(args[0:])
	configurationParameters = deploy.AppendExistingValuesNotConfigured(k8sClient, applicationSpec, configurationParameters)
	configurationParameters = deploy.AppendDefaultValuesForMissingConfigurationValues(applicationSpec, configurationParameters)
	configurationKeyValues, validationError := deploy.ValidateConfigurationAgainstDescriptor(applicationSpec, configurationParameters)

	if validationError != nil {
		util.LogAndExit("%s", validationError.Error())
	}

	createNamespaceIfNotExist(k8sClient, applicationSpec)

	if pulledImage.Authenticated {
		if err := verifyPasswordOptions(opts); err == nil {
			if terminal.IsTerminal(int(os.Stdin.Fd())) && (opts.username == "" || opts.password == "") {
				if !dockerConfigEntryExists(k8sClient, namespace, dockerRegistryURL) {
					username, password := promptCredentials(dockerRegistryURL)
					createOrUpdateImagePullSecret(k8sClient, namespace, dockerRegistryURL, username, password)
				}
			} else if opts.username != "" && opts.password != "" {
				createOrUpdateImagePullSecret(k8sClient, namespace, dockerRegistryURL, opts.username, opts.password)
			} else {
				util.LogAndExit("Please provide username and password, by using both --username and --password-stdin, or, by using both --username and --password, or omit these flags to get prompted for username and password.")
			}
		} else {
			util.LogAndExit("%s", err)
		}
	}

	// Get the Cloudflow operator ownerReference
	ownerReference := version.GetOwnerReferenceForCloudflowOperator()

	serviceAccount := newCloudflowServiceAccountWithImagePullSecrets(namespace, ownerReference)

	if _, err := createOrUpdateServiceAccount(k8sClient, namespace, serviceAccount); err != nil {
		util.LogAndExit("%s", err)
	}

	// Delay the creation of the secret for after the ownerReferences has been added.
	// Creating then updating the secret generates problem for the Flink streamlets deployment.
	streamletNameSecretMap := deploy.CreateSecretsData(&applicationSpec, configurationKeyValues)

	applicationSpec, err = copyReplicaConfigurationFromCurrentApplication(cloudflowApplicationClient, applicationSpec)
	if err != nil {
		util.LogAndExit("The application descriptor is invalid, %s", err.Error())
	}

	createOrUpdateCloudflowApplication(cloudflowApplicationClient, applicationSpec, ownerReference)

	// When the CR has been created, create a ownerReference using the uid from the stored CR and
	// then update secrets and service account with the ownerReference
	storedCR, err := cloudflowApplicationClient.Get(applicationSpec.AppID)
	if err != nil {
		util.LogAndExit("Failed to retrieve the application `%s`, %s", applicationSpec.AppID, err.Error())
	}
	ownerReference = storedCR.GenerateOwnerReference()

	streamletNameSecretMap = deploy.UpdateSecretsWithOwnerReference(ownerReference, streamletNameSecretMap)
	createOrUpdateStreamletSecrets(k8sClient, namespace, streamletNameSecretMap)

	serviceAccount.ObjectMeta.OwnerReferences = []metav1.OwnerReference{ownerReference}
	if _, err := createOrUpdateServiceAccount(k8sClient, namespace, serviceAccount); err != nil {
		util.LogAndExit("%s", err)
	}

	util.PrintSuccess("Deployment of application `%s` has started.\n", namespace)
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

func dockerConfigEntryExists(k8sClient *kubernetes.Clientset, namespace string, dockerRegistryURL string) bool {
	if serviceAccount, nserr := k8sClient.CoreV1().ServiceAccounts(namespace).Get(cloudflowAppServiceAccountName, metav1.GetOptions{}); nserr == nil {
		for _, secret := range serviceAccount.ImagePullSecrets {
			if secret, err := k8sClient.CoreV1().Secrets(namespace).Get(secret.Name, metav1.GetOptions{}); err == nil {
				var config docker.ConfigJSON
				if err := json.Unmarshal(secret.Data[".dockerconfigjson"], &config); err == nil {
					_, exists := config.Auths[dockerRegistryURL]
					if exists == true {
						return exists
					}
				}

				var dockerConfig docker.Config
				if err := json.Unmarshal(secret.Data[".dockercfg"], &dockerConfig); err == nil {
					_, exists := dockerConfig[dockerRegistryURL]
					if exists == true {
						return exists
					}
				}
			}
		}
	}
	return false
}

func copyReplicaConfigurationFromCurrentApplication(applicationClient *k8s.CloudflowApplicationClient, spec cloudflowapplication.CloudflowApplicationSpec) (cloudflowapplication.CloudflowApplicationSpec, error) {

	app, err := applicationClient.Get(spec.AppID)
	if err != nil {
		// Not found
		return spec, nil
	}

	replicas := make(map[string]int)
	for i := range app.Spec.Deployments {
		for _, newDeployment := range spec.Deployments {
			if app.Spec.Deployments[i].StreamletName == newDeployment.StreamletName {
				if app.Spec.Deployments[i].Replicas != nil {
					replicas[app.Spec.Deployments[i].StreamletName] = *app.Spec.Deployments[i].Replicas
				}
			}
		}
	}

	for name, replicas := range replicas {
		if spec, err := scale.UpdateDeploymentWithReplicas(spec, name, replicas); err != nil {
			return spec, err
		}
	}

	return spec, nil
}

func createOrUpdateStreamletSecrets(k8sClient *kubernetes.Clientset, namespace string, streamletNameSecretMap map[string]*corev1.Secret) {
	for streamletName, secret := range streamletNameSecretMap {
		if _, err := k8sClient.CoreV1().Secrets(secret.ObjectMeta.Namespace).Get(secret.ObjectMeta.Name, metav1.GetOptions{}); err != nil {
			if _, err := k8sClient.CoreV1().Secrets(namespace).Create(secret); err != nil {
				util.LogAndExit("Failed to create secret %s, %s", streamletName, err.Error())
			}
		} else {
			if _, err := k8sClient.CoreV1().Secrets(namespace).Update(secret); err != nil {
				util.LogAndExit("Failed to update secret %s, %s", streamletName, err.Error())
			}
		}
	}
}

func createNamespaceIfNotExist(k8sClient *kubernetes.Clientset, applicationSpec cloudflowapplication.CloudflowApplicationSpec) {
	ns := cloudflowapplication.NewCloudflowApplicationNamespace(applicationSpec)
	if _, nserr := k8sClient.CoreV1().Namespaces().Get(ns.ObjectMeta.Name, metav1.GetOptions{}); nserr != nil {
		if _, nserr := k8sClient.CoreV1().Namespaces().Create(&ns); nserr != nil {
			util.LogAndExit("Failed to create namespace [%s], %s", applicationSpec.AppID, nserr.Error())
		}
	}
}

func createOrUpdateCloudflowApplication(cloudflowApplicationClient *k8s.CloudflowApplicationClient, spec cloudflowapplication.CloudflowApplicationSpec, cloudflowOperatorOwnerReference metav1.OwnerReference) {

	storedCR, errCR := cloudflowApplicationClient.Get(spec.AppID)

	if errCR == nil {
		cloudflowApplication := cloudflowapplication.UpdateCloudflowApplication(spec, storedCR.ObjectMeta.ResourceVersion)
		_, err := cloudflowApplicationClient.Update(cloudflowApplication)
		if err != nil {
			util.LogAndExit("Failed to update CloudflowApplication `%s`, %s", spec.AppID, err.Error())
		}
	} else if reflect.DeepEqual(*storedCR, cloudflowapplication.CloudflowApplication{}) {
		cloudflowApplication := cloudflowapplication.NewCloudflowApplication(spec)
		cloudflowApplication.SetOwnerReferences([]metav1.OwnerReference{cloudflowOperatorOwnerReference})

		_, err := cloudflowApplicationClient.Create(cloudflowApplication)
		if err != nil {
			util.LogAndExit("Failed to create CloudflowApplication `%s`, %s", spec.AppID, err.Error())
		}
	} else {
		if strings.Contains(errCR.Error(), "the server could not find the requested resource") {
			util.LogAndExit("Cannot create a Cloudflow application because the Cloudflow application custom resource defintion has not yet been installed on the cluster.")
		}
		util.LogAndExit("Failed to determine if Cloudflow application already have been created, %s", errCR.Error())
	}
}
