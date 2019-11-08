package cmd

import (
	"io"
	"io/ioutil"
	"os"
	"os/user"
	"path/filepath"
	"runtime"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/spf13/cobra"
	yaml "gopkg.in/yaml.v2"

	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

type flag struct {
	Name        string `yaml:"name"`
	Shorthand   string `yaml:"shorthand"`
	Description string `yaml:"desc"`
}

type subcommand struct {
	Name             string `yaml:"name"`
	ShortDescription string `yaml:"shortDesc"`
	LongDescription  string `yaml:"longDesc"`
	Example          string `yaml:"example"`
	Command          string `yaml:"command"`
	Flags            []flag `yaml:"flags"`
}

type plugin struct {
	Name             string       `yaml:"name"`
	ShortDescription string       `yaml:"shortDesc"`
	LongDescription  string       `yaml:"longDesc"`
	Example          string       `yaml:"example"`
	Command          string       `yaml:"command"`
	Flags            []flag       `yaml:"flags"`
	Tree             []subcommand `yaml:"tree"`
}

type ocPluginCMD struct {
	cmd *cobra.Command
}

const ocPluginCommandName = "install-oc-plugin"

func init() {

	pluginCMD := &ocPluginCMD{}
	pluginCMD.cmd = &cobra.Command{
		Use:   ocPluginCommandName,
		Short: "Installs the Cloudflow OpenShift CLI `oc` plugin.",
		Long:  "Installs the Cloudflow OpenShift CLI `oc` plugin. After installation all commands can be accessed by executing `oc plugin cloudflow`.",
		Run:   pluginCMD.pluginImpl,
	}
	rootCmd.AddCommand(pluginCMD.cmd)
}

func (c *ocPluginCMD) pluginImpl(cmd *cobra.Command, args []string) {

	if runtime.GOOS == "windows" {
		util.LogAndExit("OC integration is not supported on Windows. Please use 'kubectl' instead.")
	}

	usr, err := user.Current()
	if err != nil {
		util.LogAndExit("Failed to retrieve the current user, %s", err.Error())
	}
	// For OC Plugin paths etc see below
	// https://docs.openshift.com/container-platform/3.11/cli_reference/extend_cli.html
	path := usr.HomeDir + "/.kube/plugins/cloudflow/"
	yamlFilename := "plugin.yaml"
	if pathErr := os.MkdirAll(path, os.ModePerm); pathErr != nil {
		util.LogAndExit("Failed to create path '%s' for plugin, %s", path, err.Error())

	}
	plugin := plugin{}
	plugin.Name = "cloudflow"
	plugin.Command = path + plugin.Name
	plugin.ShortDescription = rootCmd.Short
	plugin.LongDescription = rootCmd.Long

	for _, cmd := range rootCmd.Commands() {
		if !cmd.Hidden && cmd.Name() != ocPluginCommandName {
			subcommand := subcommand{}
			subcommand.Name = cmd.Name()
			subcommand.ShortDescription = cmd.Short
			subcommand.LongDescription = cmd.Long
			subcommand.Example = cmd.Example
			subcommand.Command = plugin.Command + " " + cmd.Use
			plugin.Tree = append(plugin.Tree, subcommand)
		}
	}
	bytes, _ := yaml.Marshal(&plugin)
	if err := ioutil.WriteFile(path+yamlFilename, bytes, 0644); err != nil {
		util.LogAndExit("Failed to create plugin configuration file %s, %s", path+yamlFilename, err.Error())
	}

	fullPath, pathErr := filepath.Abs(os.Args[0])
	if pathErr != nil {
		util.LogAndExit("Failed to resolve path to 'kubectl-cloudflow', %s", pathErr.Error())
	}

	if copyErr := copyFile(fullPath, plugin.Command); copyErr != nil {
		util.LogAndExit("Failed to copy executable file %s to %s, %s", fullPath, plugin.Command, copyErr.Error())
	}

	if modeErr := os.Chmod(plugin.Command, 0755); modeErr != nil {
		util.LogAndExit("Failed to make file %s executable, %s", plugin.Command, err.Error())
	}

	util.PrintSuccess(`Installation done!
Please review the full list of Cloudflow commands using the following command:

	oc plugin cloudflow`)
}

func copyFile(src, dst string) error {

	source, err := os.Open(src)
	if err != nil {
		return err
	}
	defer source.Close()

	destination, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer destination.Close()
	_, copyErr := io.Copy(destination, source)
	return copyErr
}
