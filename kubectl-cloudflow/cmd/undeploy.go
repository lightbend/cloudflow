package cmd

import (
	"bufio"
	"fmt"
	"os"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/k8s"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/kafka"
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
		Long:  "An interactive command to undeploy a Cloudflow application. The data managed by Cloudflow for the application can optionally be removed. Flags can be added to the command line to make the command non-interactive.",
		Run:   undeployCMD.undeployImpl,
	}
	undeployCMD.cmd.Flags().BoolVarP(&undeployCMD.removeDataFlag, "remove-data", "r", false, "Removes all PVCs and Kafka topics managed by Cloudflow.")
	undeployCMD.cmd.Flags().BoolVarP(&undeployCMD.keepDataFlag, "keep-data", "k", false, "Only removes the application. PVCs and Kafka topics will not be removed.")

	rootCmd.AddCommand(undeployCMD.cmd)
}

// undeployImpl removes one or more applications and optionally the Cloudflow managed data.
func (c *undeployApplicationsCMD) undeployImpl(cmd *cobra.Command, args []string) {

	removeData := false

	if c.removeDataFlag == true && c.keepDataFlag == true {
		util.LogAndExit("`--remove-data` and `--keep-data` cannot be set at the same time.")
	}

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

	removeData = askForRemovalOfData(c)

	if _, errDel := cloudflowApplicationClient.Delete(applicationID); errDel != nil {
		util.LogAndExit("Failed to undeploy application `%s`, %s.", applicationID, errDel.Error())
	} else {
		fmt.Printf("Undeployment of application `%s` started.\n", applicationID)
		if removeData {
			kafka.RemoveKafkaTopics(applicationID)
			k8s.RemovePersistentVolumeClaims(applicationID)
		}
	}

	if removeData == false {
		fmt.Println("")
		fmt.Println("To find the Kafka topics previously managed by this application, use the following command:")
		fmt.Printf("kubectl get kafkatopics -n lightbend -l app.kubernetes.io/managed-by=\"cloudflow\" -l app.kubernetes.io/part-of=\"%s\"\n", applicationID)
		fmt.Println("")
		fmt.Println("To find the PVCs previously managed by this application, use the following command:")
		fmt.Printf("kubectl get pvc -n %s -l app.kubernetes.io/managed-by=\"cloudflow\"\n", applicationID)
		fmt.Println("")
	}
	fmt.Println("")
	util.PrintSuccess("The command completed successfully.")
}

func askForRemovalOfData(c *undeployApplicationsCMD) bool {
	if c.removeDataFlag == false && c.keepDataFlag == false {
		fmt.Println(`
Do you want to remove all Persistent Volume Claims and Kafka topics managed by Cloudflow for this application? 

Cloudflow applications create Kafka topics and Persistent Volume Claims for storing data while running.
When undeploying an application you can chose to remove these as well.
If the PVCs and Kafka topics are kept, processing will start where it left off when the application is deployed again.

To learn more about PVCs and Kafka topics:
Persistent Volumes and Claims - https://kubernetes.io/docs/concepts/storage/persistent-volumes/
Kafka topics - https://kafka.apache.org/documentation/#intro_topics`)

		fmt.Print("yes/[no] > ")
		reader := bufio.NewReader(os.Stdin)
		yesNo, _ := reader.ReadString('\n')
		if strings.TrimSpace(yesNo) == "yes" {
			return true
		}
	} else {
		if c.removeDataFlag == true {
			return true
		}
		if c.keepDataFlag == true {
			return false
		}
	}

	return false
}
