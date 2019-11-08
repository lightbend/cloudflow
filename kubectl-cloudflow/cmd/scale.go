package cmd

import (
	"fmt"
	"strconv"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/scale"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/version"

	"github.com/spf13/cobra"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

type scaleApplicationCMD struct {
	cmd *cobra.Command
}

func init() {

	scaleCMD := &scaleApplicationCMD{}
	scaleCMD.cmd = &cobra.Command{
		Use:     "scale",
		Short:   "Scales a streamlet of a deployed Cloudflow application to the specified number of replicas.",
		Example: "kubectl cloudflow my-app my-streamlet 2",
		Run:     scaleCMD.scaleImpl,
		Args:    validateScaleCmdArgs,
	}
	rootCmd.AddCommand(scaleCMD.cmd)
}

func (c *scaleApplicationCMD) scaleImpl(cmd *cobra.Command, args []string) {
	version.FailOnProtocolVersionMismatch()

	applicationName := args[0]
	streamletName := args[1]
	replicas, err := strconv.Atoi(args[2])
	if err != nil {
		util.LogAndExit("scale factor must be a number, %s", err.Error())
	}
	cloudflowApplicationClient, err := k8s.GetCloudflowApplicationClient(applicationName)
	if err != nil {
		util.LogAndExit("Failed to create new client, %s", err.Error())
	}

	applicationCR, err := cloudflowApplicationClient.Get(applicationName)
	if err != nil {
		util.LogAndExit("Failed to retrieve the application `%s`, %s", applicationName, err.Error())
	}

	// Check that the streamlet exists
	foundStreamlet := false
	for _, v := range applicationCR.Spec.Streamlets {
		if v.Name == streamletName {
			foundStreamlet = true
			break
		}
	}

	if foundStreamlet == false {
		util.LogAndExit("Streamlet %s is not found in the application.", streamletName)
	}

	if applicationCR.Spec, err = scale.UpdateDeploymentWithReplicas(applicationCR.Spec, streamletName, replicas); err != nil {
		util.LogAndExit("The application descriptor is invalid, %s", err.Error())
	}

	if _, err := cloudflowApplicationClient.Update(*applicationCR); err != nil {
		util.LogAndExit("Failed to update the application %s, %s", applicationCR.ObjectMeta.Name, err.Error())
	}

	util.PrintSuccess("Streamlet %s in application %s is being scaled to %v replicas.\n", streamletName, applicationName, replicas)
}

func validateScaleCmdArgs(cmd *cobra.Command, args []string) error {

	if len(args) < 3 {
		return fmt.Errorf("You need to specify an application name, streamlet and scale factor")
	}

	if value, err := strconv.Atoi(args[2]); err != nil || value < 1 {
		return fmt.Errorf("The scale factor needs to be expressed as a number greater or equal to one. (%s)", args[2])
	}

	return nil
}
