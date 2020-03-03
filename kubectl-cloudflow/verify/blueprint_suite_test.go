package verify

import (
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
	"testing"
)

func TestBlueprintSpecs(t *testing.T) {
	RegisterFailHandler(Fail)
	RunSpecs(t, "Bluerprint Spec Suite")
}
