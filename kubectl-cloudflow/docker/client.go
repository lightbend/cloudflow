package docker

import (
	"context"
	"fmt"
	"io"
	"io/ioutil"
	"os"
	"os/exec"
	"path"
	"sort"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/client"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

type dockerEvent struct {
	Status         string `json:"status"`
	Error          string `json:"error"`
	Progress       string `json:"progress"`
	ProgressDetail struct {
		Current int `json:"current"`
		Total   int `json:"total"`
	} `json:"progressDetail"`
}

// ConfigJSON contains auths for pulling from image repositories
type ConfigJSON struct {
	Auths Config `json:"auths"`
}

// Config represents the config file used by the docker CLI.
// This represents the credentials that should be used
// when pulling images from specific image repositories.
type Config map[string]ConfigEntry

// ConfigEntry an entry in the Config
type ConfigEntry struct {
	Username string `json:"username"`
	Password string `json:"password"`
	Auth     string `json:"auth"`
}

// PulledImage represents the image that was pulled.
type PulledImage struct {
	ImageName     string
	Authenticated bool
	Digest        string
}

// GetClient returns a Docker client.Client structure that contains a connection to the docker daemon, note that you need to specify a version number
func GetClient(version string) (*client.Client, error) {
	return client.NewClientWithOpts(client.WithVersion(version))
}

func GetClientForAPIVersionWithFallback() (*client.Client, error) {
	apiversion, apierr := exec.Command("docker", "version", "--format", "'{{.Server.APIVersion}}'").Output()
	if apierr != nil {
		return nil, fmt.Errorf("Could not get docker API version, is the docker daemon running? API error: %s", apierr.Error())
	}

	trimmedapiversion := strings.Trim(string(apiversion), "\t \n\r'")
	client, error := GetClient(trimmedapiversion)
	if error != nil {
		client, error = GetClient("1.39")
		if error != nil {
			fmt.Printf("No compatible version of the Docker server API found, tried version %s and 1.39", trimmedapiversion)
			return nil, error
		}
	}
	return client, nil
}

// PullImage pulls an image from a remote repository
func PullImage(cli *client.Client, imageName string) (*PulledImage, error) {
	ctx := context.Background()
	out, err := cli.ImagePull(ctx, imageName, types.ImagePullOptions{})
	if err != nil {
		cmd := exec.Command("docker", "pull", imageName)
		cmd.Stderr = os.Stderr
		runErr := cmd.Run()

		if runErr != nil {
			return nil, runErr
		}
		digest := GetImageDigest(cli, imageName)
		return &PulledImage{imageName, true, digest}, nil
	}
	digest := GetImageDigest(cli, imageName)
	io.Copy(ioutil.Discard, out)
	defer out.Close()
	return &PulledImage{imageName, false, digest}, nil
}

// GetImageDigest gets the imageDigest for the imageName
func GetImageDigest(cli *client.Client, imageName string) string {
	images, err := cli.ImageList(context.Background(), types.ImageListOptions{})

	if err != nil {
		util.LogAndExit("Failed to list local docker images, %s", err.Error())
	}

	for _, image := range images {
		if len(image.RepoDigests) == 0 || len(image.RepoTags) == 0 || !imageMatchesNameAndTag(image, imageName) {
			// not the image we are looking for
			continue
		}
		_, imageDigest := path.Split(image.RepoDigests[0])
		return imageDigest
	}
	util.LogAndExit("Unable to inspect image '%s'. It could not be found locally.", imageName)
	return "" // never reached
}

// In case the overall label value exceeded 64K, the scaal side has split into
// multiple labels. In that case we need to join the values from all labels to
// for the final value of the label
func getAllLabelValuesJoined(image types.ImageSummary, labelBase string) string {
	var labelNames []string
	for k := range image.Labels {
		if strings.Contains(k, labelBase) {
			labelNames = append(labelNames, k)
		}
	}
	// optimization - makes sense since this will be
	// the most frequent path
	if len(labelNames) == 1 {
		return image.Labels[labelNames[0]]
	}
	labelValues := make([]string, 0, len(labelNames))
	sort.Strings(labelNames)
	for _, labelName := range labelNames {
		labelValues = append(labelValues, image.Labels[labelName])
	}
	return strings.Join(labelValues, "")
}

func imageMatchesNameAndTag(image types.ImageSummary, imageNameAndTag string) bool {
	for _, nameAndTag := range image.RepoTags {
		if nameAndTag == imageNameAndTag || fmt.Sprintf("docker.io/%s", nameAndTag) == imageNameAndTag {
			return true
		}
	}
	return false
}
