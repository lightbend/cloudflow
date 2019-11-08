package cmd

import (
	"os"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/spf13/cobra"
)

type generateAutocompletionCmd struct {
	cmd       *cobra.Command
	shellType string
}

func init() {

	completionRoot := &cobra.Command{
		Use:   "completion",
		Short: "Generate auto completion configurations for this tool.",
	}
	completionRoot.Hidden = true
	rootCmd.AddCommand(completionRoot)

	generateAutocompletion := &generateAutocompletionCmd{}
	generateAutocompletion.cmd = &cobra.Command{
		Use:   "generate",
		Short: "Generate an auto complete script to stdout.",
		Run:   generateAutocompletion.generateAutocompletionImpl,
	}
	generateAutocompletion.cmd.Flags().StringVarP(&generateAutocompletion.shellType, "shell", "s", "bash", "supported shells are [bash] or [zsh].")
	completionRoot.AddCommand(generateAutocompletion.cmd)
}

func (c *generateAutocompletionCmd) generateAutocompletionImpl(cmd *cobra.Command, args []string) {
	switch c.shellType {
	case "bash":
		if err := rootCmd.GenBashCompletion(os.Stdout); err != nil {
			panic(err.Error())
		}
	case "zsh":
		if err := rootCmd.GenZshCompletion(os.Stdout); err != nil {
			panic(err.Error())
		}
	default:
		util.LogAndExit("Shell type `%s` is not supported", c.shellType)
	}
}
