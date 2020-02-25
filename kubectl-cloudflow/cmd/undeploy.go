package cmd

import (
	"fmt"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/version"

	"github.com/spf13/cobra"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

type undeployApplicationsCMD struct {
	cmd            *cobra.Command
	removeDataFlag bool
	keepDataFlag   bool
}

func init() {

	undeployCMD := &undeployApplicationsCMD{}
	undeployCMD.cmd = &cobra.Command{
		Use:   "undeploy",
		Short: "Undeploys a Cloudflow application.",
		Long:  "An interactive command to undeploy a Cloudflow application. The data managed by Cloudflow for the application is removed as well.",
		Run:   undeployCMD.undeployImpl,
	}

	rootCmd.AddCommand(undeployCMD.cmd)
}

// undeployImpl removes one or more applications and optionally the Cloudflow managed data.
func (c *undeployApplicationsCMD) undeployImpl(cmd *cobra.Command, args []string) {

	if len(args) < 1 {
		util.LogAndExit("You have to specify an application to undeploy.")
	}

	if len(args) > 1 {
		util.LogAndExit("You can only specify one application to undeploy.")
	}

	version.FailOnProtocolVersionMismatch()

	applicationID := args[0]
	fmt.Println("")
	cloudflowApplicationClient, err := k8s.GetCloudflowApplicationClient(applicationID)

	if err != nil {
		util.LogAndExit("Failed to connect to cluster, %s", err.Error())
	}
	_, errCR := cloudflowApplicationClient.Get(applicationID)

	if errCR != nil {
		util.LogAndExit("Cannot undeploy application `%s`, %s", applicationID, errCR.Error())
	}

	if _, errDel := cloudflowApplicationClient.Delete(applicationID); errDel != nil {
		util.LogAndExit("Failed to undeploy application `%s`, %s.", applicationID, errDel.Error())
	} else {
		fmt.Printf("Undeployment of application `%s` started.\n", applicationID)

	}

	fmt.Println("")
	util.PrintSuccess("The command completed successfully.")
}
