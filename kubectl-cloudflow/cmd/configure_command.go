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
		Example: `kubectl cloudflow configure my-app --conf my-config.conf

Configuration files in HOCON format can be passed through with the --conf flag. 
Configuration files are merged by concatenating the files passed with --conf flags. 
The last --conf [file] argument can override values specified in earlier --conf [file] arguments.
In the example below, where the same configuration path is used in file1.conf and file2.conf, 
the configuration value in file2.conf takes precedence, overriding the value provided by file1.conf:

kubectl cloudflow configure swiss-knife --conf file1.conf --conf file2.conf

It is also possible to pass configuration values directly through the command-line as '[config-key]=value' pairs separated by
a space. The [config-key] must be an absolute path to the value, exactly how it would be defined in a config file. 
Some examples:

kubectl cloudflow configure swiss-knife cloudflow.runtimes.spark.config.spark.driver.memoryOverhead=512
kubectl cloudflow configure swiss-knife cloudflow.streamlets.spark-process.config-parameters.configurable-message='SPARK-OUTPUT:'


The arguments passed with '[config-key]=[value]' pairs take precedence over the files passed through with the --conf flag.
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

	appConfig, err := config.GetAppConfiguration(args, namespace, appCR.Spec, c.configFiles)
	if err != nil {
		printutil.LogErrorAndExit(err)
	}

	err = config.ReferencedPersistentVolumeClaimsExist(appConfig, namespace, appCR.Spec, k8sClient)
	if err != nil {
		printutil.LogErrorAndExit(err)
	}

	appInputSecret, err := config.CreateAppInputSecret(&appCR.Spec, appConfig)
	if err != nil {
		printutil.LogErrorAndExit(err)
	}

	// Get the Cloudflow operator ownerReference
	ownerReference := appCR.GenerateOwnerReference()

	appInputSecret = config.UpdateSecretWithOwnerReference(ownerReference, appInputSecret)

	createOrUpdateAppInputSecret(k8sClient, namespace, appInputSecret)

	printutil.PrintSuccess("Configuration of application %s has been updated.\n", applicationName)
}

func validateConfigureCMDArgs(cmd *cobra.Command, args []string) error {

	if len(args) < 1 {
		return fmt.Errorf("you need to specify a Cloudflow application name and the required configuration key/value pair(s)")
	}

	return nil
}
