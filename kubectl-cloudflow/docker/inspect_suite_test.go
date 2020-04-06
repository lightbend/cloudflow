package docker

import (
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
	"testing"
)

func TestInspectSpecs(t *testing.T) {
	RegisterFailHandler(Fail)
	RunSpecs(t, "Inspect Spec Suite")
}
