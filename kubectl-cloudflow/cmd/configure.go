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
	cmd *cobra.Command
}

func init() {

	configureCMD := &configureApplicationCMD{}
	configureCMD.cmd = &cobra.Command{
		Use:   "configure",
		Short: "Configures a deployed Cloudflow application.",
		Example: `kubectl cloudflow configure my-app mystreamlet.hostname=localhost

or to list all required configuration parameters:

kubectl cloudflow configure my-app`,

		Run:  configureCMD.configureImpl,
		Args: validateConfigureCMDArgs,
	}
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

	configurationParameters := deploy.SplitConfigurationParameters(args[1:])
	configurationParameters = deploy.AppendExistingValuesNotConfigured(k8sClient, applicationCR.Spec, configurationParameters)
	configurationParameters = deploy.AppendDefaultValuesForMissingConfigurationValues(applicationCR.Spec, configurationParameters)

	configurationKeyValues, validationError := deploy.ValidateConfigurationAgainstDescriptor(applicationCR.Spec, configurationParameters)

	if validationError != nil {
		util.LogErrorAndExit(validationError)
	}

	streamletNameSecretMap := deploy.CreateSecretsData(&applicationCR.Spec, configurationKeyValues)
	for streamletName, secret := range streamletNameSecretMap {
		if _, err := k8sClient.CoreV1().Secrets(applicationName).Update(secret); err != nil {
			util.LogAndExit("Failed to update secret %s, %s", streamletName, err.Error())
		}
	}

	util.PrintSuccess("Configuration of application %s has been updated.\n", applicationName)
}

func validateConfigureCMDArgs(cmd *cobra.Command, args []string) error {

	if len(args) < 1 {
		return fmt.Errorf("You need to specify a Cloudflow application name and the required configuration key/value pair(s)")
	}

	return nil
}
