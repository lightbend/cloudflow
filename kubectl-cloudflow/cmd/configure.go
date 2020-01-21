package cmd

import (
	"fmt"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/deploy"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
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

	cloudflowApplicationClient, err := k8s.GetCloudflowApplicationClient(applicationName)
	if err != nil {
		util.LogAndExit("Failed to create new client for Cloudflow application `%s`, %s", applicationName, err.Error())
	}

	k8sClient, k8sErr := k8s.GetClient()
	if k8sErr != nil {
		util.LogAndExit("Failed to create new kubernetes client for Cloudflow application `%s`, %s", applicationName, k8sErr.Error())
	}

	applicationCR, err := cloudflowApplicationClient.Get(applicationName)
	if err != nil {
		util.LogAndExit("Failed to retrieve the application `%s`, %s", applicationName, err.Error())
	}
	namespace := applicationCR.Spec.AppID

	configurationArguments := deploy.SplitConfigurationParameters(args[1:])

	deploy.HandleConfig(k8sClient, namespace, applicationCR.Spec, configurationArguments, c.configFiles)

	util.PrintSuccess("Configuration of application %s has been updated.\n", applicationName)
}

func validateConfigureCMDArgs(cmd *cobra.Command, args []string) error {

	if len(args) < 1 {
		return fmt.Errorf("You need to specify a Cloudflow application name and the required configuration key/value pair(s)")
	}

	return nil
}
