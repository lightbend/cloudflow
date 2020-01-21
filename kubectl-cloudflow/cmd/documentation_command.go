package cmd

import (
	"fmt"
	"os"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/printutil"
	"github.com/spf13/cobra"
	"github.com/spf13/cobra/doc"
)

type generateDocumentationCmd struct {
	cmd    *cobra.Command
	format string
	path   string
}

func init() {
	documentationRoot := &cobra.Command{
		Use:   "documentation",
		Short: "Generate documentation for this tool.",
	}
	documentationRoot.Hidden = true
	rootCmd.AddCommand(documentationRoot)

	generateDocumentation := &generateDocumentationCmd{}
	generateDocumentation.cmd = &cobra.Command{
		Use:   "generate",
		Short: "This command generate reference documentation in either markdown or man format.",
		Run:   generateDocumentation.generateDocumentationImpl,
	}
	generateDocumentation.cmd.Flags().StringVarP(&generateDocumentation.format, "format", "f", "", "supported format are [md] (markdown) or [man] (*nix man).")
	generateDocumentation.cmd.MarkFlagRequired("format")
	generateDocumentation.cmd.Flags().StringVarP(&generateDocumentation.path, "path", "p", "", "path where to store the generated files.")
	generateDocumentation.cmd.MarkFlagRequired("path")
	documentationRoot.AddCommand(generateDocumentation.cmd)
}

func (c *generateDocumentationCmd) generateDocumentationImpl(cmd *cobra.Command, args []string) {

	switch c.format {
	case "md":
		os.MkdirAll(c.path, 0700)
		if err := doc.GenMarkdownTree(rootCmd, c.path); err != nil {
			panic(err)
		}
	case "man":
		os.MkdirAll(c.path, 0700)
		if err := doc.GenManTree(rootCmd, nil, c.path); err != nil {
			panic(err)
		}

		fmt.Println(fmt.Sprintf("Please add the directory `%s` to the environment variable `manpath`.", c.path))
	default:
		printutil.LogAndExit("Format `%s` is not a supported format", c.format)
	}
}
