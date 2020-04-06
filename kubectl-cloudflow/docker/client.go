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
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/client"
	_ "k8s.io/client-go/plugin/pkg/client/auth" // Import additional authentication methods
)

const(
	streamletDescriptorsLabelName = "com.lightbend.cloudflow.streamlet-descriptors"
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

// GetVersionedClient returns a client with the proper version
func GetVersionedClient() (*client.Client, error) {
	apiversion, apierr := exec.Command("docker", "version", "--format", "'{{.Server.APIVersion}}'").Output()
	if apierr != nil {
		return nil, fmt.Errorf("Could not get docker API version, is the docker daemon running? API error: %s", apierr.Error())
	}

	trimmedapiversion := strings.Trim(string(apiversion), "\t \n\r'")
	client, err := GetClient(trimmedapiversion)
	if err != nil {
		client, err = GetClient("1.39")
		if err != nil {
			return nil, fmt.Errorf("No compatible version of the Docker server API found, tried version %s and 1.39", trimmedapiversion)
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

// GetStreamletDescriptorsForImageWithRegistryInspection extracts the configuration of a Cloudflow label from a docker image
// and the image with digest in a struct
func GetStreamletDescriptorsForImageWithRegistryInspection(imageName string)(cloudflowapplication.CloudflowStreamletDescriptorsDigestPair, string, *PulledImage, error) {
	ins := InspectOptions{Image: &ImageOptions{}}
	localInspectOuput, err := ins.InspectLocalDockerImage(imageName)
	if err != nil {
		// not found locally, check the remote registry
		remoteInspectOutput, err := ins.InspectRemoteDockerImage(imageName)

		if err !=  nil {
			return cloudflowapplication.CloudflowStreamletDescriptorsDigestPair{}, "", nil, fmt.Errorf("No label %s is found in remote image: %s", streamletDescriptorsLabelName, imageName)
		} else {
			if remoteInspectOutput != nil {
				return  parseImageInspectData(*remoteInspectOutput, imageName)
			} else {
				return cloudflowapplication.CloudflowStreamletDescriptorsDigestPair{}, "", nil, fmt.Errorf("No label %s is found in remote image: %s", streamletDescriptorsLabelName, imageName)
			}
		}
	} else {
		if localInspectOuput != nil {
			return parseImageInspectData(*localInspectOuput, imageName)
		} else {
			return cloudflowapplication.CloudflowStreamletDescriptorsDigestPair{}, "", nil, fmt.Errorf("No label %s is found in local image: %s", streamletDescriptorsLabelName, imageName)
		}
	}
}

// parseImageInspectData gets inspection data and the imageName and returns streamlet descriptors and other related info about the inspected image
func parseImageInspectData(outputData InspectOutput, imageName string)(cloudflowapplication.CloudflowStreamletDescriptorsDigestPair, string, *PulledImage, error) {
	label, found := outputData.Labels[streamletDescriptorsLabelName]
	if found {
		descriptors, err := getDescriptorsFromCompressedLabel(label)
		if err != nil {
			return cloudflowapplication.CloudflowStreamletDescriptorsDigestPair{}, "", nil, fmt.Errorf("Failed to get streamlet descriptors from compressed Labelfor image: %s, %s", imageName, err.Error())
		} else {
			var descriptorsDigest cloudflowapplication.CloudflowStreamletDescriptorsDigestPair
			descriptorsDigest.ImageDigest = outputData.Digest.String()
			descriptorsDigest.StreamletDescriptors = descriptors.StreamletDescriptors
			return descriptorsDigest, descriptors.APIVersion, &PulledImage{ImageName:imageName, Authenticated:true}, nil
		}
	} else {
		return cloudflowapplication.CloudflowStreamletDescriptorsDigestPair{}, "", nil, fmt.Errorf("No label %s is found in local image: %s", streamletDescriptorsLabelName, imageName)
	}
}

// getDescriptorsFromCompressedLabel gets the streamlet descriptors out of a compressed label
func getDescriptorsFromCompressedLabel(label string)(*cloudflowapplication.Descriptors, error) {
	compressed := base64.NewDecoder(base64.StdEncoding, bytes.NewReader([]byte(label)))
	reader, err := zlib.NewReader(compressed)
	if err != nil {
		return nil, fmt.Errorf("Failed to decompress the Cloudflow streamlet descriptors label, %s", err.Error())
	}
	uncompressed, err := ioutil.ReadAll(reader)
	if err != nil {
		return nil, fmt.Errorf("Failed to read the decompressed Cloudflow streamlet descriptors label %s", err.Error())
	}
	var descriptors cloudflowapplication.Descriptors
	err = json.Unmarshal([]byte(uncompressed), &descriptors)

	defer func() {
		err := reader.Close()
		if err != nil {
			// most likely we dont want to fail here, skip the error
			fmt.Println("Failed to close zlib reader")
		}
	}()
	return &descriptors, nil
}
