package cmd

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

var rootCmd = &cobra.Command{
	Use:               "cloudflow",
	Short:             "Create, manage, deploy, and operate Cloudflow applications.",
	Long:              "This command line tool can be used to deploy and operate Cloudflow applications.",
	DisableAutoGenTag: true,
}

// Execute bootstraps Cobra
func Execute() {
	if err := rootCmd.Execute(); err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
}
