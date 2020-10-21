package image

import (
	"fmt"
	"regexp"
	"strings"

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
