package cmd

import (
	"fmt"
	"github.com/go-akka/configuration"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/verify"
	"github.com/spf13/cobra"
	"io"
	"io/ioutil"
	"net/http"
	"net/url"
	"os"
	"path"
	"strings"
	"time"
)

type verifyBlueprintCMD struct {
	cmd *cobra.Command
}

func init() {
	verifyBlueprintCMD := &verifyBlueprintCMD{}
	verifyBlueprintCMD.cmd = &cobra.Command{
		Use:   "verify",
		Short: "Verifies the blueprint of a Cloudflow Application.",
		Example: "kubectl cloudflow verify <blueprint-file-name>",
		Run:   verifyBlueprintCMD.verifyImpl,
		Args:    validateVerifyCmdArgs,
	}
	rootCmd.AddCommand(verifyBlueprintCMD.cmd)
}

func (c *verifyBlueprintCMD) verifyImpl(cmd *cobra.Command, args []string) {
	// check blueprint contents here
	blueprint:= args[0]
	timeout := time.Duration(1 * time.Second)
	client := http.Client{
		Timeout: timeout,
	}

	blueprintURL, err := url.Parse(blueprint)
	if blueprintURL == nil || err != nil {
		util.LogAndExit("You need to specify the full path to a blueprint file. %s, is malformed. Error: %s", blueprint, err.Error())
	} else {
		if blueprintURL.Scheme == "" { // this is a local file
			config, err := loadBlueprintConfigContent(blueprint)
			if err != nil {
				util.LogAndExit("failed to parse the blueprint contents")
			}
			println(config.String())
			err = verify.VerifyBlueprint(config)
			if err != nil {
				util.LogAndExit("Blueprint verification failed. Error: %s", err.Error())
			} else {
				print("Blueprint verification succeeded.")
			}

		} else { // this is a normal url
		    tempDir, err := getTempDir()

		    if err!= nil {
		    	util.LogAndExit("Local temp dir not properly defined. Error: %s", err.Error())
			}
			resp, err := client.Get(blueprint)

			if err != nil {
				util.LogAndExit("You need to specify the full path to a blueprint file. %s, is not accessible. Error: %s ", blueprint, err.Error())
			}

			if resp == nil || resp.StatusCode != 200 {
				util.LogAndExit("You need to specify the full path to a blueprint file. %s, is not accessible", blueprint)
			}

			file, err := ioutil.TempFile(tempDir, path.Base(blueprintURL.Path)+".")
			if err != nil {
				util.LogAndExit("Could not create a temp file. Error: %s",  err.Error())
			}

			if _, err = io.Copy(file, resp.Body); err != nil {
				util.LogAndExit("Could not copy the remote blueprint file.  Error: %s",  err.Error())
			}

			if err = file.Close(); err != nil {
				util.LogAndExit("Failed to close the file.  Error: %s",  err.Error())
			}
			config, _ := loadBlueprintConfigContent(file.Name())
			println(config.String())
			err = verify.VerifyBlueprint(config)
			if err != nil {
				util.LogAndExit("Blueprint verification failed. Error: %s", err.Error())
			} else {
				print("Blueprint verification succeeded.")
			}
		}
	}
}

func getTempDir() (string, error) {
	tempDir, isPresent := os.LookupEnv("CLOUDFLOW_BLUEPRINT_DOWNLOAD_DIR")
	if !isPresent {
		return os.TempDir(), nil
	} else {
		if len(tempDir) == 0 {
			return "", fmt.Errorf("You need to specify a non-empty CLOUDFLOW_BLUEPRINT_DOWNLOAD_DIR env var")
		} else {
			return tempDir, nil
		}
	}
}

func validateVerifyCmdArgs(cmd *cobra.Command, args []string) error {
	if len(args) != 1 {
		return fmt.Errorf("you need to specify the full path to a blueprint file")
	}
	blueprint:= args[0]
	blueprintURL, err := url.Parse(blueprint)

	if err != nil {
		return fmt.Errorf("You need to specify the full path to a blueprint file. %s, is malformed.", blueprint)
	}

	if blueprintURL.Scheme == "" {
		if !util.FileExists(blueprint) {
			return fmt.Errorf("You need to specify the full path to a blueprint file. %s, local file does not exist.", blueprint)
		}
	}
	return nil
}

func loadBlueprintConfigContent(path string) (*configuration.Config, error) {
	var sb strings.Builder
	println(path)
	content, err := ioutil.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("could not read configuration file %s", content)
	}
	sb.Write(content)
	sb.WriteString("")
	config := configuration.ParseString(sb.String())
	return config, nil
}
