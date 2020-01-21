package dockerclient

import (
	"context"
	"fmt"
	"io"
	"io/ioutil"
	"os"
	"os/exec"
	"path"
	"regexp"
	"sort"
	"strings"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/client"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/printutil"
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
	ImageName         string
	Authenticated     bool
	Digest            string
	DockerRegistryURL string
}

// GetClient returns a Docker client.Client structure that contains a connection to the docker daemon, note that you need to specify a version number
func GetClient(version string) (*client.Client, error) {
	return client.NewClientWithOpts(client.WithVersion(version))
}

// GetClientForAPIVersionWithFallback gets a docker client for API version that matches installed docker cli version.
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
func PullImage(cli *client.Client, imageName string, dockerRegistryURL string) (*PulledImage, error) {
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
		return &PulledImage{imageName, true, digest, dockerRegistryURL}, nil
	}
	digest := GetImageDigest(cli, imageName)
	io.Copy(ioutil.Discard, out)
	defer out.Close()
	return &PulledImage{imageName, false, digest, dockerRegistryURL}, nil
}

// GetImageDigest gets the imageDigest for the imageName
func GetImageDigest(cli *client.Client, imageName string) string {
	images, err := cli.ImageList(context.Background(), types.ImageListOptions{})

	if err != nil {
		printutil.LogAndExit("Failed to list local docker images, %s", err.Error())
	}

	for _, image := range images {
		if len(image.RepoDigests) == 0 || len(image.RepoTags) == 0 || !imageMatchesNameAndTag(image, imageName) {
			// not the image we are looking for
			continue
		}
		_, imageDigest := path.Split(image.RepoDigests[0])
		return imageDigest
	}
	printutil.LogAndExit("Unable to inspect image '%s'. It could not be found locally.", imageName)
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

// ImageReference is a reference to a docker image
type ImageReference struct {
	Registry   string
	Repository string
	Image      string
	Tag        string
}

// ParseImageReference parse an imageURI
func ParseImageReference(imageURI string) (*ImageReference, error) {

	imageRef := strings.TrimSpace(imageURI)
	msg := "The following docker image path is not valid:\n\n%s\n\nA common error is to prefix the image path with a URI scheme like 'http' or 'https'."

	if strings.HasPrefix(imageRef, ":") ||
		strings.HasSuffix(imageRef, ":") ||
		strings.HasPrefix(imageRef, "http://") ||
		strings.HasPrefix(imageRef, "https://") {
		return nil, fmt.Errorf(msg, imageRef)
	}

	/*
	 See https://docs.dockerclient.com/engine/reference/commandline/tag/
	 A tag name must be valid ASCII and may contain lowercase and uppercase letters, digits, underscores, periods and dashes.
	 A tag name may not start with a period or a dash and may contain a maximum of 128 characters.
	 A tag contain lowercase and uppercase letters, digits, underscores, periods and dashes
	 (It can also contain a : which the docs don't mention, for instance sha256:<hash>)
	*/
	imageRefRegex := regexp.MustCompile(`^((?P<reg>([a-zA-Z0-9-.:]{0,253}))/)?(?P<repo>(?:[a-z0-9-_./]+/)?)(?P<image>[a-z0-9-_.]+)(?:[:@](?P<tag>[^.-][a-zA-Z0-9-_.:]{0,127})?)?$`)
	match := imageRefRegex.FindStringSubmatch(imageRef)

	if match == nil {
		return nil, fmt.Errorf(msg, imageRef)
	}

	result := make(map[string]string)
	for i, name := range imageRefRegex.SubexpNames() {
		if i != 0 && name != "" && i < len(match) {
			result[name] = match[i]
		}
	}

	ir := ImageReference{result["reg"], strings.TrimSuffix(result["repo"], "/"), result["image"], result["tag"]}

	if ir.Image == "" {
		return nil, fmt.Errorf(msg, imageRef)
	}

	if strings.HasPrefix(ir.Image, ":") || strings.HasSuffix(ir.Image, ":") {
		return nil, fmt.Errorf(msg, imageRef)
	}

	if strings.HasPrefix(ir.Tag, ".") || strings.HasPrefix(ir.Tag, "-") || strings.HasPrefix(ir.Tag, ":") || strings.HasSuffix(ir.Tag, ":") {
		return nil, fmt.Errorf(msg, imageRef)
	}

	if strings.Count(ir.Tag, ":") > 1 {
		return nil, fmt.Errorf(msg, imageRef)
	}

	// this is a shortcoming in using a regex for this, since it will always eagerly match the first part as the registry.
	if ir.Registry != "" && ir.Repository == "" {
		ir.Repository = ir.Registry
		ir.Registry = ""
	}

	return &ir, nil
}
