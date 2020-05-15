package cmd

import (
	"fmt"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/version"
	"github.com/spf13/cobra"
)

func init() {
	rootCmd.AddCommand(versionCmd)
}

var versionCmd = &cobra.Command{
	Use:   "version",
	Short: "Prints the plugin version.",
	Run: func(cmd *cobra.Command, args []string) {
		fmt.Printf("%s (%s)\n", version.ReleaseTag, version.BuildNumber)
	},
}
