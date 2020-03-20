package docker

import (
	"bytes"
	"compress/zlib"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"io/ioutil"
	"os"
	"os/exec"
	"path"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/client"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

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
}

// GetClient returns a Docker client.Client structure that contains a connection to the docker daemon, note that you need to specify a version number
func GetClient(version string) (*client.Client, error) {
	return client.NewClientWithOpts(client.WithVersion(version))
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
		return &PulledImage{imageName, true}, nil
	}
	io.Copy(ioutil.Discard, out)
	defer out.Close()
	return &PulledImage{imageName, false}, nil
}

// GetCloudflowApplicationDescriptor extracts the configuration of a Cloudflow label from a docker image
// and the image with digest in a struct
func GetCloudflowApplicationDescriptor(cli *client.Client, imageName string) cloudflowapplication.CloudflowApplicationDescriptorDigestPair {
	images, err := cli.ImageList(context.Background(), types.ImageListOptions{})

	if err != nil {
		util.LogAndExit("Failed to list local docker images, %s", err.Error())
	}

	var descriptorDigest cloudflowapplication.CloudflowApplicationDescriptorDigestPair
	for _, image := range images {
		if len(image.RepoDigests) == 0 || len(image.RepoTags) == 0 || !imageMatchesNameAndTag(image, imageName) {
			// not the image we are looking for
			continue
		}

		// check if a not-compressed application descriptor is present
		// 1.0.0 backward compatibility support.
		// From 1.0.1, the sbt plugin populates the .zlib label with compressed data
		if raw, ok := image.Labels["com.lightbend.cloudflow.application"]; ok {
			dataBytes, err := base64.StdEncoding.DecodeString(raw)
			if err != nil {
				util.LogAndExit("Failed to recover the application descriptor from the docker image, %s", err.Error())
			}
			descriptorDigest.AppDescriptor = string(dataBytes)
		} else {
			// use the compressed application descriptor
			// base 64 value
			raw = image.Labels["com.lightbend.cloudflow.application.zlib"]
			// compressed data
			compressed := base64.NewDecoder(base64.StdEncoding, bytes.NewReader([]byte(raw)))
			reader, err := zlib.NewReader(compressed)
			if err != nil {
				util.LogAndExit("Failed to decompress the application descriptor, %s", err.Error())
			}
			// uncompressed string
			uncompressed := new(bytes.Buffer)
			_, err = uncompressed.ReadFrom(reader)
			if err != nil {
				util.LogAndExit("Failed to decompress the application descriptor, %s", err.Error())
			}
			descriptorDigest.AppDescriptor = uncompressed.String()
			reader.Close()
		}
		_, descriptorDigest.ImageDigest = path.Split(image.RepoDigests[0])
		return descriptorDigest
	}
	util.LogAndExit("Unable to inspect image '%s'. It could not be found locally.", imageName)
	return cloudflowapplication.CloudflowApplicationDescriptorDigestPair{} // never reached
}

func imageMatchesNameAndTag(image types.ImageSummary, imageNameAndTag string) bool {
	for _, nameAndTag := range image.RepoTags {
		if nameAndTag == imageNameAndTag || fmt.Sprintf("docker.io/%s", nameAndTag) == imageNameAndTag {
			return true
		}
	}
	return false
}

// GetCloudflowStreamletDescriptorsForImage extracts the configuration of a Cloudflow label from a docker image
// and the image with digest in a struct
func GetCloudflowStreamletDescriptorsForImage(cli *client.Client, imageName string) (cloudflowapplication.CloudflowStreamletDescriptorsDigestPair, string) {

	_, pullError := PullImage(cli, imageName)
	if pullError != nil {
		util.LogAndExit("Failed to pull image %s: %s", imageName, pullError.Error())
	}

	images, err := cli.ImageList(context.Background(), types.ImageListOptions{})

	streamletDescriptorsLabelName := "com.lightbend.cloudflow.streamlet-descriptors"

	if err != nil {
		util.LogAndExit("Failed to list local docker images, %s", err.Error())
	}

	var descriptorsDigest cloudflowapplication.CloudflowStreamletDescriptorsDigestPair
	for _, image := range images {
		if len(image.RepoDigests) == 0 || len(image.RepoTags) == 0 || !imageMatchesNameAndTag(image, imageName) {
			// not the image we are looking for
			continue
		}

		// use the compressed streamlet descriptors base 64 value
		raw , ok := image.Labels[streamletDescriptorsLabelName]

		if !ok {
			util.LogAndExit("Docker image %s does not have the appropriate Cloudflow streamlet descriptors label.", imageName)
		}

		// compressed data
		compressed := base64.NewDecoder(base64.StdEncoding, bytes.NewReader([]byte(raw)))
		reader, err := zlib.NewReader(compressed)
		if err != nil {
			util.LogAndExit("Failed to decompress the Cloudflow streamlet descriptors label for image %s, %s", imageName, err.Error())
		}

		// uncompressed data : []byte
		uncompressed, err := ioutil.ReadAll(reader)
		if err != nil {
			util.LogAndExit("Failed to read the decompressed Cloudflow streamlet descriptors label for image %s, %s", imageName, err.Error())
		}

		// unmarshall to struct
		var descriptors cloudflowapplication.Descriptors
		json.Unmarshal([]byte(uncompressed), &descriptors)

		descriptorsDigest.StreamletDescriptors = descriptors.StreamletDescriptors
		reader.Close()
		_, descriptorsDigest.ImageDigest = path.Split(image.RepoDigests[0])
		return descriptorsDigest, descriptors.APIVersion
	}
	util.LogAndExit("Unable to inspect image '%s'. It could not be found locally.", imageName)
	return cloudflowapplication.CloudflowStreamletDescriptorsDigestPair{}, "" // never reached
}
