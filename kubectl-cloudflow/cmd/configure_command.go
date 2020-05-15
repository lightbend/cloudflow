package cmd

import (
	"fmt"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/config"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/printutil"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/version"

	"github.com/spf13/cobra"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

type configureApplicationCMD struct {
	cmd         *cobra.Command
	configFiles []string
}

func init() {

	configureCMD := &configureApplicationCMD{}
	configureCMD.cmd = &cobra.Command{
		Use:   "configure",
		Short: "Configures a deployed Cloudflow application.",
		Example: `kubectl cloudflow configure my-app mystreamlet.hostname=localhost

TODO DOCUMENT

The arguments to the command consists of optionally one
or more '[streamlet-name].[configuration-parameter]=[value]' pairs, separated by
a space. If the key does not point to a streamlet name in the blueprint, it is used to set application level configuration values for all streamlets, 
for instance using 'akka.loglevel=DEBUG' (assuming there is no streamlet named akka) will set the akka loglevel to DEBUG for all streamlets.

Configuration files in HOCON format can be passed through with the --conf flag. All configuration files are merged in the order that they are passed through.
The streamlet arguments passed with '[streamlet-name].[configuration-parameter]=[value]' pairs take precedence over the files passed through with the --conf flag.
`,

		Run:  configureCMD.configureImpl,
		Args: validateConfigureCMDArgs,
	}
	configureCMD.cmd.Flags().StringArrayVar(&configureCMD.configFiles, "conf", []string{}, "Accepts one or more files in HOCON format.")
	rootCmd.AddCommand(configureCMD.cmd)
}

func (c *configureApplicationCMD) configureImpl(cmd *cobra.Command, args []string) {
	version.FailOnProtocolVersionMismatch()

	applicationName := args[0]

	// TODO namespace is currently always the same as application ID, this will probably change in the future.
	namespace := applicationName

	k8sClient, _, appClient := getClientsOrExit(namespace)

	appCR, err := appClient.Get(applicationName)
	if err != nil {
		printutil.LogAndExit("Failed to retrieve the application `%s`, %s", applicationName, err.Error())
	}

	streamletNameSecretMap, err := config.HandleConfig(args, k8sClient, namespace, appCR.Spec, c.configFiles)
	if err != nil {
		printutil.LogErrorAndExit(err)
	}

	// Get the Cloudflow operator ownerReference
	ownerReference := appCR.GenerateOwnerReference()

	streamletNameSecretMap = config.UpdateSecretsWithOwnerReference(ownerReference, streamletNameSecretMap)

	createOrUpdateStreamletSecrets(k8sClient, namespace, streamletNameSecretMap)

	printutil.PrintSuccess("Configuration of application %s has been updated.\n", applicationName)
}

func validateConfigureCMDArgs(cmd *cobra.Command, args []string) error {

	if len(args) < 1 {
		return fmt.Errorf("you need to specify a Cloudflow application name and the required configuration key/value pair(s)")
	}

	return nil
}
