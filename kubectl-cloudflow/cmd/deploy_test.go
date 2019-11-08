package cmd

import (
	"os"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestMain(m *testing.M) {
	code := m.Run()
	os.Exit(code)
}

func Test_parseImageReferenceBad(t *testing.T) {
	_, err := parseImageReference("!$&%**tests$%&%$&")
	assert.NotEmpty(t, err)
	_, err1 := parseImageReference(" :test: ")
	assert.NotEmpty(t, err1)
	_, err2 := parseImageReference("test:")
	assert.NotEmpty(t, err2)
	_, err3 := parseImageReference(":test:")
	assert.NotEmpty(t, err3)
	_, err4 := parseImageReference("test:test:test:test")
	assert.NotEmpty(t, err4)
	_, err5 := parseImageReference("test::test")
	assert.NotEmpty(t, err5)
	_, err6 := parseImageReference("test:.test")
	assert.NotEmpty(t, err6)
	_, err7 := parseImageReference("test:-test")
	assert.NotEmpty(t, err7)
	_, err8 := parseImageReference("http://registry/repo:test")
	assert.NotEmpty(t, err8)
	_, err9 := parseImageReference("https://registry/repo:test")
	assert.NotEmpty(t, err9)
}

func Test_parseImageReferenceEmpty(t *testing.T) {
	_, err := parseImageReference("")
	assert.NotEmpty(t, err)
	_, err2 := parseImageReference("  ")
	assert.NotEmpty(t, err2)
}

func Test_parseImageReferenceNoImage(t *testing.T) {
	_, err := parseImageReference("registry/repo/:test")
	assert.NotEmpty(t, err)
}

func Test_parseImageReferenceNoImageAndTag(t *testing.T) {
	_, err := parseImageReference(":test")
	assert.NotEmpty(t, err)
}

func Test_parseImageReferenceBadRepoName(t *testing.T) {
	_, err := parseImageReference("registry/repo*repo/image:test")
	assert.NotEmpty(t, err)
}

func Test_parseImageReferenceNoTag(t *testing.T) {
	imageRef, _ := parseImageReference("registry/repo/image")
	assert.Equal(t, "registry", imageRef.registry)
	assert.Equal(t, "repo", imageRef.repository)
	assert.Equal(t, "image", imageRef.image)
	assert.Equal(t, "", imageRef.tag)
}

func Test_parseImageReferenceDashesPeriodsUnderscores(t *testing.T) {
	imageRef, _ := parseImageReference("registry/repo_1.2-45/image_with-1.2_some-dashes")
	assert.Equal(t, "registry", imageRef.registry)
	assert.Equal(t, "repo_1.2-45", imageRef.repository)
	assert.Equal(t, "image_with-1.2_some-dashes", imageRef.image)
	assert.Equal(t, "", imageRef.tag)
}

func Test_parseImageReference(t *testing.T) {
	imageRef, _ := parseImageReference("registry/repo/image:test")
	assert.Equal(t, "registry", imageRef.registry)
	assert.Equal(t, "repo", imageRef.repository)
	assert.Equal(t, "image", imageRef.image)
	assert.Equal(t, "test", imageRef.tag)
}

func Test_parseImageReferenceOk(t *testing.T) {
	imageRef, _ := parseImageReference("docker-registry-default.purplehat.lightbend.com/lightbend/crazy-rays:386-c66cd02")
	assert.Equal(t, "docker-registry-default.purplehat.lightbend.com", imageRef.registry)
	assert.Equal(t, "lightbend", imageRef.repository)
	assert.Equal(t, "crazy-rays", imageRef.image)
	assert.Equal(t, "386-c66cd02", imageRef.tag)
}

func Test_parseImageReferencePort(t *testing.T) {
	imageRef, _ := parseImageReference("registry.com:1234/repo/image:test")
	assert.Equal(t, "registry.com:1234", imageRef.registry)
	assert.Equal(t, "repo", imageRef.repository)
	assert.Equal(t, "image", imageRef.image)
	assert.Equal(t, "test", imageRef.tag)
}

func Test_parseImageReferenceLonger(t *testing.T) {
	imageRef, _ := parseImageReference("some.long.name-with.allowed0912.registry.com:1234/repo/image:test")
	assert.Equal(t, "some.long.name-with.allowed0912.registry.com:1234", imageRef.registry)
	assert.Equal(t, "repo", imageRef.repository)
	assert.Equal(t, "image", imageRef.image)
	assert.Equal(t, "test", imageRef.tag)
}

func Test_parseImageReferenceBadDNSNameSeenAsRepo(t *testing.T) {
	imageRef, _ := parseImageReference("not_allowed_as_registry_but_ok_as_repo/repo/image:test")
	assert.Equal(t, "", imageRef.registry)
	assert.Equal(t, "not_allowed_as_registry_but_ok_as_repo/repo", imageRef.repository)
	assert.Equal(t, "image", imageRef.image)
	assert.Equal(t, "test", imageRef.tag)
}

func Test_parseImageReferenceManySlashes(t *testing.T) {
	imageRef, _ := parseImageReference("reg/repo1/sub1/sub2/sub3/image:test")
	assert.Equal(t, "reg", imageRef.registry)
	assert.Equal(t, "repo1/sub1/sub2/sub3", imageRef.repository)
	assert.Equal(t, "image", imageRef.image)
	assert.Equal(t, "test", imageRef.tag)
}

func Test_parseImageReferenceNoRepo(t *testing.T) {
	imageRef, _ := parseImageReference("image:test")
	assert.Equal(t, "", imageRef.registry)
	assert.Equal(t, "", imageRef.repository)
	assert.Equal(t, "image", imageRef.image)
	assert.Equal(t, "test", imageRef.tag)
}

func Test_parseImageReferenceNoRegistry(t *testing.T) {
	imageRef, _ := parseImageReference("grafana/grafana:test")
	assert.Equal(t, "", imageRef.registry)
	assert.Equal(t, "grafana", imageRef.repository)
	assert.Equal(t, "grafana", imageRef.image)
	assert.Equal(t, "test", imageRef.tag)
}

func Test_parseImageReferenceSha(t *testing.T) {
	imageRef, _ := parseImageReference("eu.gcr.io/bubbly-observer-178213/lightbend/rays-sensors@sha256:0840ebe4b207b9ca7eb400e84bf937b64c3809fc275b2e90ba881cbecf56c39a")
	assert.Equal(t, "eu.gcr.io", imageRef.registry)
	assert.Equal(t, "bubbly-observer-178213/lightbend", imageRef.repository)
	assert.Equal(t, "rays-sensors", imageRef.image)
	assert.Equal(t, "sha256:0840ebe4b207b9ca7eb400e84bf937b64c3809fc275b2e90ba881cbecf56c39a", imageRef.tag)
}
