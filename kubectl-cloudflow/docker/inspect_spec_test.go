// +build integration

package docker

import (
	"fmt"
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
	"time"
)

var imageNameToTest = "docker.io/lightbend/spark-aggregation:134-d0ec286-dirty"

var _ = Describe("Docker image inspection", func() {
	Context("if a docker image is valid", func() {
		It("should retrieve the Cloudflow streamlets descriptor label", func() {
			ins := NewInspectOptions()
			start := time.Now()
			output, err := ins.InspectRemoteDockerImage(imageNameToTest)
			elapsed := time.Since(start)
			fmt.Printf("dockerhub inspect took %s\n", elapsed)
			Expect(err).Should(BeNil())
			_, found := output.Labels[streamletDescriptorsLabelName]
			Expect(found).Should(BeTrue())
		})
	})

	Context("if a docker image does not exist", func() {
		It("should fail retrieving the Cloudflow streamlets descriptor  label", func() {
			ins := NewInspectOptions()
			start := time.Now()
			output, err := ins.InspectRemoteDockerImage(imageNameToTest + "nonexistent")
			elapsed := time.Since(start)
			fmt.Printf("dockerhub inspect for non-existent imge took %s\n", elapsed)
			Expect(output).Should(BeNil())
			Expect(err).ShouldNot(BeNil())
		})
	})
	
	// TODO: add tests for AWS and GCloud
})
