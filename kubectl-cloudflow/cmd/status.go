package cmd

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/version"

	"github.com/spf13/cobra"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

type getStatusCMD struct {
	cmd *cobra.Command
}

func init() {

	statusCMD := &getStatusCMD{}
	statusCMD.cmd = &cobra.Command{
		Use:     "status",
		Short:   "Gets the status of a Cloudflow application.",
		Example: "kubectl cloudflow status my-app",
		Run:     statusCMD.statusImpl,
		Args:    validateStatusCmdArgs,
	}
	rootCmd.AddCommand(statusCMD.cmd)
}

func (c *getStatusCMD) statusImpl(cmd *cobra.Command, args []string) {
	version.FailOnProtocolVersionMismatch()

	applicationName := args[0]

	cloudflowApplicationClient, err := k8s.GetCloudflowApplicationClient(applicationName)
	if err != nil {
		util.LogAndExit("Failed to create new client, %s", err.Error())
	}

	applicationCR, err := cloudflowApplicationClient.Get(applicationName)
	if err != nil {
		util.LogAndExit("Failed to retrieve the application `%s`, %s", applicationName, err.Error())
	}

	printAppStatus(applicationCR, applicationCR.Status.AppStatus)
	printEndpointStatuses(applicationCR)
	printStreamletStatuses(applicationCR)
}

func validateStatusCmdArgs(cmd *cobra.Command, args []string) error {

	if len(args) < 1 {
		return fmt.Errorf("you need to specify an application name")
	}
	return nil
}

func printAppStatus(applicationCR *domain.CloudflowApplication, appStatus string) {
	fmt.Printf("Name:             %s\n", applicationCR.Name)
	fmt.Printf("Namespace:        %s\n", applicationCR.Namespace)
	fmt.Printf("Version:          %s\n", applicationCR.Spec.AppVersion)
	fmt.Printf("Created:          %s\n", applicationCR.ObjectMeta.CreationTimestamp.String())
	fmt.Printf("Status:           %s\n", appStatus)
}

func printEndpointStatuses(applicationCR *domain.CloudflowApplication) {
	if len(applicationCR.Status.EndpointStatuses) > 0 {
		w := new(tabwriter.Writer)
		w.Init(os.Stdout, 18, 0, 1, ' ', 0)
		fmt.Fprintln(w, "STREAMLET\tENDPOINT\t")
		for _, e := range applicationCR.Status.EndpointStatuses {
			fmt.Fprintf(w, "%s\t%s\n", e.StreamletName, e.URL)
		}
		fmt.Println("")
		(*w).Flush()
	}
}

func printStreamletStatuses(applicationCR *domain.CloudflowApplication) {
	w := new(tabwriter.Writer)
	w.Init(os.Stdout, 18, 0, 1, ' ', 0)
	fmt.Fprintln(w, "STREAMLET\tPOD\tREADY\tSTATUS\tRESTARTS\t")
	for _, s := range applicationCR.Status.StreamletStatuses {
		for _, p := range s.PodStatuses {
			fmt.Fprintf(w, "%s\t%s\t%d/%d\t%s\t%d\n", s.StreamletName, p.Name, p.NrOfContainersReady, p.NrOfContainers, p.Status, p.Restarts)
		}
	}
	fmt.Println("")
	(*w).Flush()
}
