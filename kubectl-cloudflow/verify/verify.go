package verify

import (
	"fmt"
	"os/exec"
	"strings"

	"github.com/go-akka/configuration"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/docker"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
)

// VerifyBlueprint parses blueprint `images` section and fetch labels from defined images.
// It then decode labels and stores streamlet descriptors per image id.
// It uses aggregated info to parse blueprint `streamlets` and verify that streamlets in streamlets
// section exist in the relevant image label.
// It also maps descriptors, streamlets and blueprint connections to a Blueprint struct and runs verify
// logic.
// TODO: fix performance issues with getting descriptors from image refs.
//       Investigate using the docker apis instead of inspecting docker images.
func VerifyBlueprint(content string) (Blueprint, error) {
	config := configuration.ParseString(content)
	imageRefsFromBlueprint := getImageRefsFromConfig(config)

	// map imageID -> []StreamletDescriptor
	imageDescriptorMap, fallbackAppID := getStreamletDescriptorsFromImageRefs(imageRefsFromBlueprint)

	// all StreamletRefs in the blueprint
	streamletRefs := getStreamletRefsFromBlueprintConfig(config)

	// all StreamletConnections in the blueprint
	connections := getConnectionsFromBlueprintConfig(config)

	// get name
	appID := config.GetString("blueprint.name", fallbackAppID)

	blueprint := Blueprint{
		name:                         appID,
		images:                       imageRefsFromBlueprint,
		streamlets:                   streamletRefs,
		streamletDescriptorsPerImage: imageDescriptorMap,
		connections:                  connections,
	}

	blueprint = blueprint.verify()
	var errors string
	for _, p := range blueprint.globalProblems {
		errors = errors + p.ToMessage() + "\n"
	}
	if len(blueprint.globalProblems) == 0 {
		return blueprint, nil
	}
	return Blueprint{}, fmt.Errorf("%s", errors)
}

// this map is constructed entirely from blueprint
func getImageRefsFromConfig(config *configuration.Config) map[string]cloudflowapplication.ImageReference {
	// get the images from the blueprint
	imagesFromConfig := config.GetNode("blueprint.images")

	var imageKeyVals = make(map[string]cloudflowapplication.ImageReference)
	if imagesFromConfig == nil || imagesFromConfig.GetObject() == nil {
		return imageKeyVals
	}
	var images = imagesFromConfig.GetObject().Items()
	for imageKey, imageRef := range images {
		ref, err := ParseImageReference(imageRef.GetString())
		if err != nil {
			util.LogAndExit(err.Error())
		}
		if ref != nil {
			imageKeyVals[imageKey] = *ref
		}

	}
	return imageKeyVals
}

func getConnectionsFromBlueprintConfig(config *configuration.Config) []StreamletConnection {
	connectionMapFromConfig := config.GetNode("blueprint.connections")
	var conns []StreamletConnection

	if connectionMapFromConfig == nil || connectionMapFromConfig.GetObject() == nil {
		return conns
	}
	connectionMap := connectionMapFromConfig.GetObject().Items()
	for fromStreamlet, rest := range connectionMap {
		outs := rest.GetObject().Items()
		for fromPort, ins := range outs {
			for _, in := range ins.GetArray() {
				conns = append(conns, StreamletConnection{from: fmt.Sprintf("%s.%s", fromStreamlet, fromPort), to: in.GetString(), metadata: config})
			}
		}
	}
	return conns
}

func getStreamletRefsFromBlueprintConfig(config *configuration.Config) []StreamletRef {
	streamletsFromConfig := config.GetNode("blueprint.streamlets")
	var streamletRefs []StreamletRef

	if streamletsFromConfig == nil || streamletsFromConfig.GetObject() == nil {
		return streamletRefs
	}
	streamlets := streamletsFromConfig.GetObject().Items()
	for name, classWithImage := range streamlets {
		arr := strings.Split(classWithImage.GetString(), "/")
		streamletRef := StreamletRef{name: name, className: arr[1], imageId: &arr[0], metadata: config}
		streamletRefs = append(streamletRefs, streamletRef)
	}
	return streamletRefs
}

func getStreamletDescriptorsFromImageRefs(imageRefs map[string]cloudflowapplication.ImageReference) (map[string][]StreamletDescriptor, string) {
	apiversion, apierr := exec.Command("docker", "version", "--format", "'{{.Server.APIVersion}}'").Output()
	if apierr != nil {
		util.LogAndExit("Could not get docker API version, is the docker daemon running? API error: %s", apierr.Error())
	}

	trimmedapiversion := strings.Trim(string(apiversion), "\t \n\r'")
	client, err := docker.GetClient(trimmedapiversion)
	if err != nil {
		client, err = docker.GetClient("1.39")
		if err != nil {
			util.LogAndExit("No compatible version of the Docker server API found, tried version %s and 1.39. Error is: %s", trimmedapiversion, err.Error())
		}
	}

	// get all streamlet descriptors, image digests and pulled images in arrays
	var streamletDescriptors = make(map[string][]StreamletDescriptor)
	var fallbackAppID string
	for key, imageRef := range imageRefs {
		streamletsDescriptorsDigestPair, version, err := docker.GetStreamletDescriptorsForImage(client, imageRef.FullURI)
		if err != nil {
			util.LogAndExit(err.Error())
		}
		if version != cloudflowapplication.SupportedApplicationDescriptorVersion {
			util.LogAndExit("Image %s is incompatible and no longer supported. Please update sbt-cloudflow and rebuild the image.", imageRef.FullURI)
		}
		var sdescs = make([]StreamletDescriptor, len(streamletsDescriptorsDigestPair.StreamletDescriptors))
		for _, desc := range streamletsDescriptorsDigestPair.StreamletDescriptors {
			sdescs = append(sdescs, StreamletDescriptor(desc))
		}
		streamletDescriptors[key] = sdescs
		if fallbackAppID == "" {
			fallbackAppID = strings.Split(streamletsDescriptorsDigestPair.ImageDigest, "@")[0]
		}
	}
	return streamletDescriptors, fallbackAppID
}
