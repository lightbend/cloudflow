package cmd

import (
	"fmt"
	"net/url"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/fileutils"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/verify"
	"github.com/spf13/cobra"
)

type verifyOptions struct {
	cmd *cobra.Command
}

func init() {
	verifyOpts := &verifyOptions{}
	verifyOpts.cmd = &cobra.Command{
		Use:     "verify",
		Short:   "Verifies the blueprint of a Cloudflow Application.",
		Long: `Verifies a Cloudflow blueprint file. This command allows to explicitly verify a blueprint without deploying it.
Blueprint verification will inspect the images in the "images"" section of the blueprint,
get the streamlet descriptors from the image labels and will proceed to the verification of the rest of the blueprint
sections which are "streamlets" and "connections". 
The command either succeeds or prints a report with issues found during the verification process.

kubectl cloudflow verify ./call-record-aggregator-blueprint.conf`,
		Example: `> kubectl cloudflow verify call-record-aggregator-blueprint.conf`,
		Run:     verifyOpts.verifyImpl,
		Args:    validateVerifyCmdArgs,
	}

	rootCmd.AddCommand(verifyOpts.cmd)
}

// verifyImpl is the entry point for the blueprint verify command
// if blueprint contents cannot be read we exit immediately.
func (c *verifyOptions) verifyImpl(cmd *cobra.Command, args []string) {
	// check blueprint contents here
	blueprint := args[0]
	contents, err := fileutils.GetFileContents(blueprint)
	contents = strings.TrimSpace(contents)
	if err != nil {
		util.LogAndExit("Failed to fetch blueprint contents from %s", blueprint)
	}

	// TODO: do proper syntactic analysis here
	if len(contents) == 0 {
		util.LogAndExit("Blueprint is empty. Path is: %s", blueprint)
	}

	_, _, _, err = verify.VerifyBlueprint(contents)
	if err != nil {
		util.LogAndExit("Blueprint verification failed. Error: %s", err.Error())
	} else {
		util.PrintSuccess("Blueprint verification succeeded.")
	}
}

// validateVerifyCmdArgs validates the args for the verify command.
// we only accept one argument at the moment which is a path to the blueprint.
func validateVerifyCmdArgs(cmd *cobra.Command, args []string) error {
	if len(args) != 1 {
		return fmt.Errorf("Please specify the full path to a blueprint file")
	}
	blueprint := args[0]
	blueprintURL, err := url.Parse(blueprint)

	if err != nil {
		return fmt.Errorf("You need to specify the full path to a blueprint file. '%s', is malformed", blueprint)
	}

	if blueprintURL.Scheme == "" {
		if !fileutils.FileExists(blueprint) {
			return fmt.Errorf("You need to specify the full path to a blueprint file. Local file '%s' does not exist", blueprint)
		}
	}
	return nil
}
