package cmd

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cfapp"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/printutil"
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

	cloudflowApplicationClient, err := cfapp.GetCloudflowApplicationClient(applicationName)
	if err != nil {
		printutil.LogAndExit("Failed to create new client, %s", err.Error())
	}

	applicationCR, err := cloudflowApplicationClient.Get(applicationName)
	if err != nil {
		printutil.LogAndExit("Failed to retrieve the application `%s`, %s", applicationName, err.Error())
	}

	if applicationCR.Status != nil {
		printAppStatus(applicationCR, applicationCR.Status.AppStatus)
		printEndpointStatuses(applicationCR)
		printStreamletStatuses(applicationCR)
	} else {
		printutil.LogAndExit("%s status is unknown", applicationCR.Name)
	}

}

func validateStatusCmdArgs(cmd *cobra.Command, args []string) error {

	if len(args) < 1 {
		return fmt.Errorf("you need to specify an application name")
	}
	return nil
}

func printAppStatus(applicationCR *cfapp.CloudflowApplication, appStatus string) {
	fmt.Printf("Name:             %s\n", applicationCR.Name)
	fmt.Printf("Namespace:        %s\n", applicationCR.Namespace)
	fmt.Printf("Version:          %s\n", applicationCR.Spec.AppVersion)
	fmt.Printf("Created:          %s\n", applicationCR.ObjectMeta.CreationTimestamp.String())
	fmt.Printf("Status:           %s\n", appStatus)
}

func printEndpointStatuses(applicationCR *cfapp.CloudflowApplication) {
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

func printStreamletStatuses(applicationCR *cfapp.CloudflowApplication) {
	w := new(tabwriter.Writer)
	w.Init(os.Stdout, 18, 0, 1, ' ', 0)
	fmt.Fprintln(w, "STREAMLET\tPOD\tREADY\tSTATUS\tRESTARTS\t")
	for _, s := range applicationCR.Status.StreamletStatuses {
		if len(s.PodStatuses) == 0 {
			fmt.Fprintf(w, "%s\t%s\t%d/%d\t%s\t%d\n", s.StreamletName, "", 0, 0, "Missing", 0)
		} else {
			for _, p := range s.PodStatuses {
				fmt.Fprintf(w, "%s\t%s\t%d/%d\t%s\t%d\n", s.StreamletName, p.Name, p.NrOfContainersReady, p.NrOfContainers, p.Status, p.Restarts)
			}
		}
	}
	fmt.Println("")
	(*w).Flush()
}
