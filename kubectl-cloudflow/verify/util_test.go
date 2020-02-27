package verify

import (
	"github.com/stretchr/testify/assert"
	"os"
	"testing"
)

func TestMain(m *testing.M) {
	code := m.Run()
	os.Exit(code)
}

func Test_ParseImageReferenceBad(t *testing.T) {
	_, err := ParseImageReference("!$&%**tests$%&%$&")
	assert.NotEmpty(t, err)
	_, err1 := ParseImageReference(" :test: ")
	assert.NotEmpty(t, err1)
	_, err2 := ParseImageReference("test:")
	assert.NotEmpty(t, err2)
	_, err3 := ParseImageReference(":test:")
	assert.NotEmpty(t, err3)
	_, err4 := ParseImageReference("test:test:test:test")
	assert.NotEmpty(t, err4)
	_, err5 := ParseImageReference("test::test")
	assert.NotEmpty(t, err5)
	_, err6 := ParseImageReference("test:.test")
	assert.NotEmpty(t, err6)
	_, err7 := ParseImageReference("test:-test")
	assert.NotEmpty(t, err7)
	_, err8 := ParseImageReference("http://registry/repo:test")
	assert.NotEmpty(t, err8)
	_, err9 := ParseImageReference("https://registry/repo:test")
	assert.NotEmpty(t, err9)
}

func Test_ParseImageReferenceEmpty(t *testing.T) {
	_, err := ParseImageReference("")
	assert.NotEmpty(t, err)
	_, err2 := ParseImageReference("  ")
	assert.NotEmpty(t, err2)
}

func Test_ParseImageReferenceNoImage(t *testing.T) {
	_, err := ParseImageReference("registry/repo/:test")
	assert.NotEmpty(t, err)
}

func Test_ParseImageReferenceNoImageAndTag(t *testing.T) {
	_, err := ParseImageReference(":test")
	assert.NotEmpty(t, err)
}

func Test_ParseImageReferenceBadRepoName(t *testing.T) {
	_, err := ParseImageReference("registry/repo*repo/image:test")
	assert.NotEmpty(t, err)
}

func Test_ParseImageReferenceNoTag(t *testing.T) {
	imageRef, _ := ParseImageReference("registry/repo/image")
	assert.Equal(t, "registry", imageRef.Registry)
	assert.Equal(t, "repo", imageRef.Repository)
	assert.Equal(t, "image", imageRef.Image)
	assert.Equal(t, "", imageRef.Tag)
}

func Test_ParseImageReferenceDashesPeriodsUnderscores(t *testing.T) {
	imageRef, _ := ParseImageReference("registry/repo_1.2-45/image_with-1.2_some-dashes")
	assert.Equal(t, "registry", imageRef.Registry)
	assert.Equal(t, "repo_1.2-45", imageRef.Repository)
	assert.Equal(t, "image_with-1.2_some-dashes", imageRef.Image)
	assert.Equal(t, "", imageRef.Tag)
}

func Test_ParseImageReference(t *testing.T) {
	imageRef, _ := ParseImageReference("registry/repo/image:test")
	assert.Equal(t, "registry", imageRef.Registry)
	assert.Equal(t, "repo", imageRef.Repository)
	assert.Equal(t, "image", imageRef.Image)
	assert.Equal(t, "test", imageRef.Tag)
}

func Test_ParseImageReferenceOk(t *testing.T) {
	imageRef, _ := ParseImageReference("docker-registry-default.purplehat.lightbend.com/lightbend/crazy-rays:386-c66cd02")
	assert.Equal(t, "docker-registry-default.purplehat.lightbend.com", imageRef.Registry)
	assert.Equal(t, "lightbend", imageRef.Repository)
	assert.Equal(t, "crazy-rays", imageRef.Image)
	assert.Equal(t, "386-c66cd02", imageRef.Tag)
}

func Test_ParseImageReferencePort(t *testing.T) {
	imageRef, _ := ParseImageReference("registry.com:1234/repo/image:test")
	assert.Equal(t, "registry.com:1234", imageRef.Registry)
	assert.Equal(t, "repo", imageRef.Repository)
	assert.Equal(t, "image", imageRef.Image)
	assert.Equal(t, "test", imageRef.Tag)
}

func Test_ParseImageReferenceLonger(t *testing.T) {
	imageRef, _ := ParseImageReference("some.long.name-with.allowed0912.registry.com:1234/repo/image:test")
	assert.Equal(t, "some.long.name-with.allowed0912.registry.com:1234", imageRef.Registry)
	assert.Equal(t, "repo", imageRef.Repository)
	assert.Equal(t, "image", imageRef.Image)
	assert.Equal(t, "test", imageRef.Tag)
}

func Test_ParseImageReferenceBadDNSNameSeenAsRepo(t *testing.T) {
	imageRef, _ := ParseImageReference("not_allowed_as_registry_but_ok_as_repo/repo/image:test")
	assert.Equal(t, "", imageRef.Registry)
	assert.Equal(t, "not_allowed_as_registry_but_ok_as_repo/repo", imageRef.Repository)
	assert.Equal(t, "image", imageRef.Image)
	assert.Equal(t, "test", imageRef.Tag)
}

func Test_ParseImageReferenceManySlashes(t *testing.T) {
	imageRef, _ := ParseImageReference("reg/repo1/sub1/sub2/sub3/image:test")
	assert.Equal(t, "reg", imageRef.Registry)
	assert.Equal(t, "repo1/sub1/sub2/sub3", imageRef.Repository)
	assert.Equal(t, "image", imageRef.Image)
	assert.Equal(t, "test", imageRef.Tag)
}

func Test_ParseImageReferenceNoRepo(t *testing.T) {
	imageRef, _ := ParseImageReference("image:test")
	assert.Equal(t, "", imageRef.Registry)
	assert.Equal(t, "", imageRef.Repository)
	assert.Equal(t, "image", imageRef.Image)
	assert.Equal(t, "test", imageRef.Tag)
}

func Test_ParseImageReferenceNoRegistry(t *testing.T) {
	imageRef, _ := ParseImageReference("grafana/grafana:test")
	assert.Equal(t, "", imageRef.Registry)
	assert.Equal(t, "grafana", imageRef.Repository)
	assert.Equal(t, "grafana", imageRef.Image)
	assert.Equal(t, "test", imageRef.Tag)
}

func Test_ParseImageReferenceSha(t *testing.T) {
	imageRef, _ := ParseImageReference("eu.gcr.io/bubbly-observer-178213/lightbend/rays-sensors@sha256:0840ebe4b207b9ca7eb400e84bf937b64c3809fc275b2e90ba881cbecf56c39a")
	assert.Equal(t, "eu.gcr.io", imageRef.Registry)
	assert.Equal(t, "bubbly-observer-178213/lightbend", imageRef.Repository)
	assert.Equal(t, "rays-sensors", imageRef.Image)
	assert.Equal(t, "sha256:0840ebe4b207b9ca7eb400e84bf937b64c3809fc275b2e90ba881cbecf56c39a", imageRef.Tag)
}

func Test_parseClassName(t *testing.T) {
	assert.Equal(t, CheckFullPatternMatch("$0000", ClassNamePattern), true)
	assert.Equal(t, CheckFullPatternMatch("0000", ClassNamePattern), false)
	assert.Equal(t, CheckFullPatternMatch("$0000.erere_$", ClassNamePattern), true)
	assert.Equal(t, CheckFullPatternMatch("__2rererere.$", ClassNamePattern), true)
	assert.Equal(t, CheckFullPatternMatch("@@@@@@$$$$__", ClassNamePattern), false)
}

func Test_parseLabelName(t *testing.T) {
	assert.Equal(t, IsDnsLabelCompatible("$0000"), false)
	assert.Equal(t, IsDnsLabelCompatible("0000www"), true)
	assert.Equal(t, IsDnsLabelCompatible("rfr@3"), false)
	assert.Equal(t, IsDnsLabelCompatible("__2rererere."), false)
	assert.Equal(t, IsDnsLabelCompatible("2212dwdwee__"), false)
}

func Test_parseConfigurationParameter(t *testing.T) {
	assert.Equal(t, CheckFullPatternMatch("0000", ConfigParameterKeyPattern), false)
	assert.Equal(t, CheckFullPatternMatch("$0000", ConfigParameterKeyPattern), false)
	assert.Equal(t, CheckFullPatternMatch("ER12qw", ConfigParameterKeyPattern), true)
	assert.Equal(t, CheckFullPatternMatch("2weee", ConfigParameterKeyPattern), false)
	assert.Equal(t, CheckFullPatternMatch("configuration.", ConfigParameterKeyPattern), true)
}
