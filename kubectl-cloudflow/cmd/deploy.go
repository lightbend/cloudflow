package cmd

import (
	"errors"
	"fmt"
	"io/ioutil"
	"os"
	"reflect"
	"regexp"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/deploy"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/docker"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/scale"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
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
	cmd           *cobra.Command
	username      string
	password      string
	passwordStdin bool
	volumeMounts  []string
}

func init() {

	deployOpts := &deployOptions{}
	deployOpts.cmd = &cobra.Command{
		Use:   "deploy",
		Short: "Deploys a Cloudflow application to the cluster.",
		Long: `Deploys a Cloudflow application to the cluster.
The arguments to the command consists of a docker image path and optionally one
or more '[streamlet-name].[configuration-parameter]=[value]' pairs, separated by
a space.

Streamlet volume mounts can be configured using the --volume-mount flag.
The flag accepts one or more key/value pair where the key is the name of the
volume mount, specified as '[streamlet-name].[volume-mount-name]', and the value
is the name of a Kubernetes Persistent Volume Claim, which needs to be located
in the same namespace as the Cloudflow application, e.g. the namespace with the
same name as the application.

  kubectl cloudflow deploy docker.registry.com/my-company/sensor-data-scala:292-c183d80 --volume-mount my-streamlet.mount=pvc-name

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
		Example: `kubectl cloudflow deploy registry.test-cluster.io/cloudflow/sensor-data-scala:292-c183d80 valid-logger.log-level=info valid-logger.msg-prefix=valid`,
		Args:    validateDeployCmdArgs,
		Run:     deployOpts.deployImpl,
	}
	deployOpts.cmd.Flags().StringVarP(&deployOpts.username, "username", "u", "", "docker registry username.")
	deployOpts.cmd.Flags().StringVarP(&deployOpts.password, "password", "p", "", "docker registry password.")
	deployOpts.cmd.Flags().BoolVarP(&deployOpts.passwordStdin, "password-stdin", "", false, "Take the password from stdin")

	deployOpts.cmd.Flags().StringArrayVar(&deployOpts.volumeMounts, "volume-mount", []string{}, "Accepts a key/value pair separated by an equal sign. The key should be the name of the volume mount, specified as '[streamlet-name].[volume-mount-name]'. The value should be the name of an existing persistent volume claim.")

	rootCmd.AddCommand(deployOpts.cmd)
}

func (opts *deployOptions) deployImpl(cmd *cobra.Command, args []string) {
	version.FailOnProtocolVersionMismatch()

	crFile := args[0]
	crString, err := util.GetFileContents(crFile)
	if err != nil {
		util.LogAndExit("%s", err.Error())
	}

	bytes := []byte(crString)

	var applicationSpec domain.CloudflowApplicationSpec
	json.Unmarshal(bytes, &applicationSpec)

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

	configurationParameters := deploy.SplitConfigurationParameters(args[1:])
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

	// Delay the creation of the secret for after the ownerReferences has been added.
	// Creating then updating the secret generates problem for the Flink streamlets deployment.
	streamletNameSecretMap := deploy.CreateSecretsData(&applicationSpec, configurationKeyValues)

	applicationSpec, err = copyReplicaConfigurationFromCurrentApplication(cloudflowApplicationClient, applicationSpec)
	if err != nil {
		util.LogAndExit("The application descriptor is invalid, %s", err.Error())
	}

	createOrUpdateCloudflowApplication(cloudflowApplicationClient, applicationSpec)

	// When the CR has been created, create a ownerReference using the uid from the stored CR and
	// then update secrets and service account with the ownerReference
	storedCR, err := cloudflowApplicationClient.Get(applicationSpec.AppID)
	if err != nil {
		util.LogAndExit("Failed to retrieve the application `%s`, %s", applicationSpec.AppID, err.Error())
	}
	ownerReference := storedCR.GenerateOwnerReference()

	streamletNameSecretMap = deploy.UpdateSecretsWithOwnerReference(ownerReference, streamletNameSecretMap)
	createOrUpdateStreamletSecrets(k8sClient, namespace, streamletNameSecretMap)

	// Delay the creation of the service account for after the ownerReferences has been generated.
	serviceAccount := newCloudflowServiceAccountWithImagePullSecrets(namespace)
	if _, err := createOrUpdateServiceAccount(k8sClient, namespace, serviceAccount, ownerReference); err != nil {
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

type imageReference struct {
	registry   string
	repository string
	image      string
	tag        string
}

func parseImageReference(imageURI string) (*imageReference, error) {

	imageRef := strings.TrimSpace(imageURI)
	msg := "The following docker image path is not valid:\n\n%s\n\nA common error is to prefix the image path with a URI scheme like 'http' or 'https'."

	if strings.HasPrefix(imageRef, ":") ||
		strings.HasSuffix(imageRef, ":") ||
		strings.HasPrefix(imageRef, "http://") ||
		strings.HasPrefix(imageRef, "https://") {
		return nil, fmt.Errorf(msg, imageRef)
	}

	/*
	 See https://docs.docker.com/engine/reference/commandline/tag/
	 A tag name must be valid ASCII and may contain lowercase and uppercase letters, digits, underscores, periods and dashes.
	 A tag name may not start with a period or a dash and may contain a maximum of 128 characters.
	 A tag contain lowercase and uppercase letters, digits, underscores, periods and dashes
	 (It can also contain a : which the docs don't mention, for instance sha256:<hash>)
	*/
	imageRefRegex := regexp.MustCompile(`^((?P<reg>([a-zA-Z0-9-.:]{0,253}))/)?(?P<repo>(?:[a-z0-9-_./]+/)?)(?P<image>[a-z0-9-_.]+)(?:[:@](?P<tag>[^.-][a-zA-Z0-9-_.:]{0,127})?)?$`)
	match := imageRefRegex.FindStringSubmatch(imageRef)

	if match == nil {
		return nil, fmt.Errorf(msg, imageRef)
	}

	result := make(map[string]string)
	for i, name := range imageRefRegex.SubexpNames() {
		if i != 0 && name != "" && i < len(match) {
			result[name] = match[i]
		}
	}

	ir := imageReference{result["reg"], strings.TrimSuffix(result["repo"], "/"), result["image"], result["tag"]}

	if ir.image == "" {
		return nil, fmt.Errorf(msg, imageRef)
	}

	if strings.HasPrefix(ir.image, ":") || strings.HasSuffix(ir.image, ":") {
		return nil, fmt.Errorf(msg, imageRef)
	}

	if strings.HasPrefix(ir.tag, ".") || strings.HasPrefix(ir.tag, "-") || strings.HasPrefix(ir.tag, ":") || strings.HasSuffix(ir.tag, ":") {
		return nil, fmt.Errorf(msg, imageRef)
	}

	if strings.Count(ir.tag, ":") > 1 {
		return nil, fmt.Errorf(msg, imageRef)
	}

	// this is a shortcoming in using a regex for this, since it will always eagerly match the first part as the registry.
	if ir.registry != "" && ir.repository == "" {
		ir.repository = ir.registry
		ir.registry = ""
	}

	return &ir, nil
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

func copyReplicaConfigurationFromCurrentApplication(applicationClient *k8s.CloudflowApplicationClient, spec domain.CloudflowApplicationSpec) (domain.CloudflowApplicationSpec, error) {

	app, err := applicationClient.Get(spec.AppID)
	if err != nil {
		// Not found
		return spec, nil
	}

	replicas := make(map[string]int)
	for i := range app.Spec.Deployments {
		for _, newDeployment := range spec.Deployments {
			if app.Spec.Deployments[i].StreamletName == newDeployment.StreamletName {
				replicas[app.Spec.Deployments[i].StreamletName] = app.Spec.Deployments[i].Replicas
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

func createNamespaceIfNotExist(k8sClient *kubernetes.Clientset, applicationSpec domain.CloudflowApplicationSpec) {
	ns := domain.NewCloudflowApplicationNamespace(applicationSpec, version.ReleaseTag, version.BuildNumber)
	if _, nserr := k8sClient.CoreV1().Namespaces().Get(ns.ObjectMeta.Name, metav1.GetOptions{}); nserr != nil {
		if _, nserr := k8sClient.CoreV1().Namespaces().Create(&ns); nserr != nil {
			util.LogAndExit("Failed to create namespace `%s`, %s", applicationSpec.AppID, nserr.Error())
		}
	}
}

func createOrUpdateCloudflowApplication(
	cloudflowApplicationClient *k8s.CloudflowApplicationClient,
	spec domain.CloudflowApplicationSpec) {

	storedCR, errCR := cloudflowApplicationClient.Get(spec.AppID)

	if errCR == nil {
		cloudflowApplication := domain.UpdateCloudflowApplication(spec, *storedCR, version.ReleaseTag, version.BuildNumber)
		_, err := cloudflowApplicationClient.Update(cloudflowApplication)
		if err != nil {
			util.LogAndExit("Failed to update CloudflowApplication `%s`, %s", spec.AppID, err.Error())
		}
	} else if reflect.DeepEqual(*storedCR, domain.CloudflowApplication{}) {
		cloudflowApplication := domain.NewCloudflowApplication(spec, version.ReleaseTag, version.BuildNumber)

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

func validateDeployCmdArgs(cmd *cobra.Command, args []string) error {

	if len(args) < 1 || args[0] == "" {
		return fmt.Errorf("please specify the full path to the file containing the application CR")
	}

	crFile := args[0]
	if !util.FileExists(crFile) {
		return fmt.Errorf("Local file (%s) does not exist", crFile)
	}

	return nil
}
