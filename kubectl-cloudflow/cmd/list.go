package cmd

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/version"

	"github.com/spf13/cobra"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

type listApplicationsCMD struct {
	cmd *cobra.Command
}

func init() {

	listCMD := &listApplicationsCMD{}
	listCMD.cmd = &cobra.Command{
		Use:   "list",
		Short: "Lists deployed Cloudflow application in the current cluster.",
		Run:   listCMD.listImpl,
	}
	rootCmd.AddCommand(listCMD.cmd)
}

func (c *listApplicationsCMD) listImpl(cmd *cobra.Command, args []string) {
	version.FailOnProtocolVersionMismatch()
	cloudflowApplicationClient, err := k8s.GetCloudflowApplicationClient("")
	if err != nil {
		util.LogAndExit("Failed to create new client, %s", err.Error())
	}

	listOfCRs, err := cloudflowApplicationClient.List()
	if err != nil {
		util.LogAndExit("Failed to get a list of deployed applications, %s", err.Error())
	}

	w := new(tabwriter.Writer)
	w.Init(os.Stdout, 18, 0, 1, ' ', 0)
	fmt.Fprintln(w, "NAME\tNAMESPACE\tVERSION\tCREATION-TIME\t")

	for _, v := range listOfCRs.Items {
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", v.Name, v.Namespace, v.Spec.AppVersion, v.ObjectMeta.CreationTimestamp.String())
	}
	(*w).Flush()

}
