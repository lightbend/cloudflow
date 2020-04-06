// This code part is copied and modified from the skopeo project,
// https://github.com/containers/skopeo.
package docker

import (
	"context"
	"fmt"
	"github.com/containers/image/v5/image"
	"github.com/containers/image/v5/manifest"
	"github.com/containers/image/v5/transports/alltransports"
	"github.com/containers/image/v5/types"
	"github.com/opencontainers/go-digest"
	"github.com/pkg/errors"
	"strings"
	"time"
)

// This code part is copied and modified from the skopeo project,
// https://github.com/containers/skopeo.
type InspectOutput struct {
	Name          string `json:",omitempty"`
	Tag           string `json:",omitempty"`
	Digest        digest.Digest
	Created       *time.Time
	DockerVersion string
	Labels        map[string]string
	Architecture  string
	Os            string
	Layers        []string
	Env           []string
}

// Inspect execution specific options
type InspectOptions struct {
	commandTimeout time.Duration
	Image  *ImageOptions
}

// ImageOptions collects info which may be different for each image
type ImageOptions struct {
	authFilePath string // Path to a */containers/auth.json
	credsOption    optionalString      // username[:password] for accessing a registry
	dockerCertPath string              // A directory using Docker-like *.{crt,cert,key} files for connecting to a registry or a daemon
	tlsVerify      bool        // Require HTTPS and verify certificates (for docker: and docker-daemon:)
	noCreds        bool                // Access the registry anonymously
	sharedBlobDir    string // A directory to use for OCI blobs, shared across repositories
	dockerDaemonHost string // docker-daemon: host to connect to
}

// optionalString is a string with a separate presence flag.
type optionalString struct {
	present bool
	value   string
}

func (opts *InspectOptions) InspectLocalDockerImage(imageName string)(imageData *InspectOutput, retErr error) {
	return opts.inspectImage("docker-daemon:" + imageName)
}

func (opts *InspectOptions) InspectRemoteDockerImage(imageName string)(imageData *InspectOutput, retErr error) {
	return opts.inspectImage("docker://" + imageName)
}

// InspectImage access a remote registry to get the image configuration.
// If no authentication arguments are passed the credentials from user's previous docker login are used.
func (opts *InspectOptions) inspectImage(imageNamewithScheme string) (imageData *InspectOutput, retErr error) {
	ctx, cancel := opts.commandTimeoutContext()
	defer cancel()

	sys, err := opts.Image.newSystemContext()
	if err != nil {
		return nil, err
	}

	src, err := parseImageSource(ctx, opts.Image, imageNamewithScheme)
	if err != nil {
		return nil, fmt.Errorf("Error parsing image name %q: %v", imageNamewithScheme, err)
	}

	defer func() {
		if err := src.Close(); err != nil {
			retErr = fmt.Errorf(fmt.Sprintf("(could not close image: %v) ", err))
		}
	}()

	rawManifest, _, err := src.GetManifest(ctx, nil)
	if err != nil {
		return nil, fmt.Errorf("Error retrieving manifest for image: %v", err)
	}

	img, err := image.FromUnparsedImage(ctx, sys, image.UnparsedInstance(src, nil))
	if err != nil {
		return nil, fmt.Errorf("Error parsing manifest for image: %v", err)
	}

	imgInspect, err := img.Inspect(ctx)
	if err != nil {
		return nil, err
	}
	outputData := InspectOutput{
		Name: "", // Set below if DockerReference() is known
		Tag:  imgInspect.Tag,
		// Digest is set below.
		Created:       imgInspect.Created,
		DockerVersion: imgInspect.DockerVersion,
		Labels:        imgInspect.Labels,
		Architecture:  imgInspect.Architecture,
		Os:            imgInspect.Os,
		Layers:        imgInspect.Layers,
		Env:           imgInspect.Env,
	}
	outputData.Digest, err = manifest.Digest(rawManifest)
	if err != nil {
		return nil, fmt.Errorf("Error computing manifest digest: %v", err)
	}
	if dockerRef := img.Reference().DockerReference(); dockerRef != nil {
		outputData.Name = dockerRef.Name()
	}

	return &outputData, nil
}

// commandTimeoutContext returns a context.Context and a cancellation callback based on opts.
// The caller should usually "defer cancel()" immediately after calling this.
func (opts *InspectOptions) commandTimeoutContext() (context.Context, context.CancelFunc) {
	ctx := context.Background()
	var cancel context.CancelFunc = func() {}
	if opts.commandTimeout > 0 {
		ctx, cancel = context.WithTimeout(ctx, opts.commandTimeout)
	}
	return ctx, cancel
}

// newSystemContext returns a *types.SystemContext corresponding to opts.
// It is guaranteed to return a fresh instance, so it is safe to make additional updates to it.
func (opts *ImageOptions) newSystemContext() (*types.SystemContext, error) {
	ctx := &types.SystemContext{
		DockerCertPath:           opts.dockerCertPath,
		OCISharedBlobDirPath:     opts.sharedBlobDir,
		AuthFilePath:             opts.authFilePath,
		DockerDaemonHost:         opts.dockerDaemonHost,
		DockerDaemonCertPath:     opts.dockerCertPath,
	}

	if opts.credsOption.present {
		var err error
		ctx.DockerAuthConfig, err = getDockerAuth(opts.credsOption.value)
		if err != nil {
			return nil, err
		}
	}

	return ctx, nil
}

// parseCreds parses user credentials (username, password)
func parseCreds(creds string) (string, string, error) {
	if creds == "" {
		return "", "", errors.New("credentials can't be empty")
	}
	up := strings.SplitN(creds, ":", 2)
	if len(up) == 1 {
		return up[0], "", nil
	}
	if up[0] == "" {
		return "", "", errors.New("username can't be empty")
	}
	return up[0], up[1], nil
}

// getDockerAuth returns the appropriate docker auth struct
// after properly parsing username and password
func getDockerAuth(creds string) (*types.DockerAuthConfig, error) {
	username, password, err := parseCreds(creds)
	if err != nil {
		return nil, err
	}
	return &types.DockerAuthConfig{
		Username: username,
		Password: password,
	}, nil
}

// parseImageSource converts image URL-like string to an ImageSource.
// The caller must call .Close() on the returned ImageSource.
func parseImageSource(ctx context.Context, opts *ImageOptions, name string) (types.ImageSource, error) {
	ref, err := alltransports.ParseImageName(name)
	if err != nil {
		return nil, err
	}
	sys, err := opts.newSystemContext()
	if err != nil {
		return nil, err
	}
	return ref.NewImageSource(ctx, sys)
}
