package verify

import (
	"github.com/go-akka/configuration"
)

// GetCloudflowApplicationDescriptorFromDockerImage pulls a image and extracts the Cloudflow Streamlet Descriptors from a docker label
//func GetCloudflowApplicationDescriptorFromDockerImage(dockerRegistryURL string, dockerRepository string, dockerImagePath string) (domain.CloudflowStreamletDescriptors, docker.PulledImage) {
//
//}


func VerifyBlueprint(config* configuration.Config) error {
	// parse blueprint `images` section and fetch labels from defined images.
	// decode labels and store streamlet descriptors per image id
	// parse blueprint `streamlets` and verify that streamlets in streamlets
	// section exist in the relevant image label.
	// map descriptors, streamlets and blueprint connections to a Blueprint struct and run verify
	return nil
}
