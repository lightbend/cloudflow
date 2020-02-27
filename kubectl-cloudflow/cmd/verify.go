package cmd

import (
	"fmt"
	"io"
	"io/ioutil"
	"net/http"
	"net/url"
	"os"
	"path"
	"strings"
	"time"

	"github.com/go-akka/configuration"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/verify"
	"github.com/spf13/cobra"
)

type verifyBlueprintCMD struct {
	cmd *cobra.Command
}

func init() {
	verifyBlueprintCMD := &verifyBlueprintCMD{}
	verifyBlueprintCMD.cmd = &cobra.Command{
		Use:     "verify",
		Short:   "Verifies the blueprint of a Cloudflow Application.",
		Example: "kubectl cloudflow verify <blueprint-file-name>",
		Run:     verifyBlueprintCMD.verifyImpl,
		Args:    validateVerifyCmdArgs,
	}
	rootCmd.AddCommand(verifyBlueprintCMD.cmd)
}

// this map is constructed entirely from blueprint
func getImageRefsFromConfig(config *configuration.Config) map[string]domain.ImageReference {
	// get the images from the blueprint
	images := config.GetNode("blueprint.images").GetObject().Items()

	var imageKeyVals = make(map[string]domain.ImageReference)

	for imageKey, imageRef := range images {
		ref, err := verify.ParseImageReference(imageRef.GetString())
		if err != nil {
			util.LogAndExit(err.Error())
		}
		imageKeyVals[imageKey] = *ref
	}
	return imageKeyVals
}

func getFilePath(pathToBlueprint string) (string, error) {
	blueprintURL, err := url.Parse(pathToBlueprint)
	if blueprintURL == nil || err != nil {
		return "", fmt.Errorf("You need to specify the full path to a blueprint file. %s, is malformed. Error: %s", pathToBlueprint, err.Error())
	}
	if blueprintURL.Scheme == "" { // this is a local file
		return pathToBlueprint, nil
	}

	// url
	timeout := time.Duration(1 * time.Second)
	client := http.Client{
		Timeout: timeout,
	}

	tempDir, err := getTempDir()

	if err != nil {
		return "", fmt.Errorf("Local temp dir not properly defined. Error: %s", err.Error())
	}
	resp, err := client.Get(pathToBlueprint)

	if err != nil {
		return "", fmt.Errorf("You need to specify the full path to a blueprint file. %s, is not accessible. Error: %s ", pathToBlueprint, err.Error())
	}

	if resp == nil || resp.StatusCode != 200 {
		return "", fmt.Errorf("You need to specify the full path to a blueprint file. %s, is not accessible", pathToBlueprint)
	}

	file, err := ioutil.TempFile(tempDir, path.Base(blueprintURL.Path)+".")
	if err != nil {
		return "", fmt.Errorf("Could not create a temp file. Error: %s", err.Error())
	}

	if _, err = io.Copy(file, resp.Body); err != nil {
		return "", fmt.Errorf("Could not copy the remote blueprint file.  Error: %s", err.Error())
	}

	if err = file.Close(); err != nil {
		return "", fmt.Errorf("Failed to close the file.  Error: %s", err.Error())
	}
	return file.Name(), nil
}

func (c *verifyBlueprintCMD) verifyImpl(cmd *cobra.Command, args []string) {
	// check blueprint contents here
	blueprint := args[0]

	file, err := getFilePath(blueprint)
	if err != nil {
		util.LogAndExit("Failed to fetch blueprint contents from %s", blueprint)
	}

	content, err := loadBlueprintFile(file)
	err = verify.VerifyBlueprint(content)
	if err != nil {
		util.LogAndExit("Blueprint verification failed. Error: %s", err.Error())
	} else {
		print("Blueprint verification succeeded.")
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
	blueprint := args[0]
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

func loadBlueprintFile(path string) (string, error) {
	var sb strings.Builder
	content, err := ioutil.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("could not read configuration file %s", content)
	}
	sb.Write(content)
	sb.WriteString("")
	return sb.String(), nil
}

func loadBlueprintConfigContent(path string) (*configuration.Config, error) {
	var sb strings.Builder
	content, err := ioutil.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("could not read configuration file %s", content)
	}
	sb.Write(content)
	sb.WriteString("")
	config := configuration.ParseString(sb.String())
	return config, nil
}
