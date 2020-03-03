package verify

import (
	"fmt"
	"os/exec"
	"strings"

	"github.com/go-akka/configuration"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/docker"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
)

// VerifyBlueprint TBD
func VerifyBlueprint(content string) error {
	// parse blueprint `images` section and fetch labels from defined images.
	// decode labels and store streamlet descriptors per image id
	// parse blueprint `streamlets` and verify that streamlets in streamlets
	// section exist in the relevant image label.
	// map descriptors, streamlets and blueprint connections to a Blueprint struct and run verify

	config := configuration.ParseString(content)
	imageRefsFromBlueprint := getImageRefsFromConfig(config)

	// map imageID -> []StreamletDescriptor
	imageDescriptorMap := getStreamletDescriptorsFromImageRefs(imageRefsFromBlueprint)

	// all StreamletRef in the blueprint
	streamletRefs := getStreamletRefsFromBlueprintConfig(config)

	// all StreamletConnection in the blueprint
	connections := getConnectionsFromBlueprintConfig(config)

	fmt.Printf("imageRefsFromBlueprint %d imageDescriptorMap %d streamletRefs %d connections %d\n",
		len(imageRefsFromBlueprint), len(imageDescriptorMap), len(streamletRefs), len(connections))

	blueprint := Blueprint{
		images:                       imageRefsFromBlueprint,
		streamlets:                   streamletRefs,
		streamletDescriptorsPerImage: imageDescriptorMap,
		connections:                  connections,
	}

	// 1. checks images section
	// 2. checks streamlets section
	// 3. checks for streamlet descriptors
	// 4. checks consistency between images section and image ids referred to in streamlets section
	// 5. checks if the image referred to in a streamlet contains the streamlet descriptor in the label present in the image
	// 6. checks connection problems
	blueprint = blueprint.verify()
	var errors string
	for _, p := range blueprint.globalProblems {
		errors = errors + p.ToMessage() + "\n"
	}
	if len(blueprint.globalProblems) == 0 {
		return nil
	}
	return fmt.Errorf("%s", errors)
}

// this map is constructed entirely from blueprint
func getImageRefsFromConfig(config *configuration.Config) map[string]domain.ImageReference {
	// get the images from the blueprint
	images := config.GetNode("blueprint.images").GetObject().Items()

	var imageKeyVals = make(map[string]domain.ImageReference)

	for imageKey, imageRef := range images {
		ref, err := ParseImageReference(imageRef.GetString())
		if err != nil {
			util.LogAndExit(err.Error())
		}
		imageKeyVals[imageKey] = *ref
	}
	return imageKeyVals
}

func getConnectionsFromBlueprintConfig(config *configuration.Config) []StreamletConnection {
	connectionMap := config.GetNode("blueprint.connections").GetObject().Items()
	var conns []StreamletConnection

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

func getConnectionsFromBlueprintConf(config *configuration.Config) []StreamletConnection {
	connectionMap := config.GetNode("blueprint.connections").GetObject().Items()
	var conns []StreamletConnection
	for from, tos := range connectionMap {
		for _, to := range tos.GetArray() {
			conns = append(conns, StreamletConnection{from: from, to: to.GetString(), metadata: config})
		}
	}
	return conns
}

func getStreamletRefsFromBlueprintConfig(config *configuration.Config) []StreamletRef {
	streamlets := config.GetNode("blueprint.streamlets").GetObject().Items()
	var streamletRefs []StreamletRef
	for name, classWithImage := range streamlets {
		arr := strings.Split(classWithImage.GetString(), "/")
		streamletRef := StreamletRef{name: name, className: arr[1], imageId: &arr[0], metadata: config}
		streamletRefs = append(streamletRefs, streamletRef)
	}
	return streamletRefs
}

func getStreamletDescriptorsFromImageRefs(imageRefs map[string]domain.ImageReference) map[string][]StreamletDescriptor {
	apiversion, apierr := exec.Command("docker", "version", "--format", "'{{.Server.APIVersion}}'").Output()
	if apierr != nil {
		util.LogAndExit("Could not get docker API version, is the docker daemon running? API error: %s", apierr.Error())
	}

	trimmedapiversion := strings.Trim(string(apiversion), "\t \n\r'")
	client, error := docker.GetClient(trimmedapiversion)
	if error != nil {
		client, error = docker.GetClient("1.39")
		if error != nil {
			fmt.Printf("No compatible version of the Docker server API found, tried version %s and 1.39", trimmedapiversion)
			panic(error)
		}
	}

	// get all streamlet descriptors, image digests and pulled images in arrays
	var streamletDescriptors = make(map[string][]StreamletDescriptor)
	for key, imageRef := range imageRefs {
		streamletsDescriptorsDigestPair, version := docker.GetCloudflowStreamletDescriptorsForImage(client, imageRef.FullURI)
		if version != domain.SupportedApplicationDescriptorVersion {
			util.LogAndExit("Image %s is incompatible and no longer supported. Please update sbt-cloudflow and rebuild the image.", imageRef.FullURI)
		}
		var sdescs = make([]StreamletDescriptor, len(streamletsDescriptorsDigestPair.StreamletDescriptors))
		for _, desc := range streamletsDescriptorsDigestPair.StreamletDescriptors {
			sdescs = append(sdescs, StreamletDescriptor(desc))
		}
		streamletDescriptors[key] = sdescs
	}
	return streamletDescriptors
}
