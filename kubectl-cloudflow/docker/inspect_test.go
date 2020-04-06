// +build integration

package docker

import (
	"fmt"
	"gotest.tools/assert"
	"testing"
	"time"
)

var imageNameToTest = "docker.io/lightbend/spark-aggregation:134-d0ec286-dirty"

func Test_inspect(t *testing.T) {
	ins := InspectOptions{Image: &ImageOptions{}}

	start := time.Now()
	output, err := ins.InspectRemoteDockerImage("docker.io/lightbend/spark-aggregation:134-d0ec286-dirty")
	elapsed := time.Since(start)
	fmt.Printf("dockerhub inspect took %s\n", elapsed)
	assert.Equal(t, err, nil)

	_, found := output.Labels[streamletDescriptorsLabelName]
	assert.Equal(t, found, true)

	client, err := GetVersionedClient()
	assert.Equal(t, err, nil)

	_, pullError := PullImage(client, "docker.io/lightbend/spark-aggregation:134-d0ec286-dirty")
	assert.Equal(t, pullError, nil)

	start = time.Now()
	output, err = ins.InspectLocalDockerImage("docker.io/lightbend/spark-aggregation:134-d0ec286-dirty")
	elapsed = time.Since(start)
	fmt.Printf("local inspect took %s\n", elapsed)
	assert.Equal(t, err, nil)

	_, found = output.Labels[streamletDescriptorsLabelName]
	assert.Equal(t, found, true)

	// TODO: refactor this to be used in integration tests with the appropriate credentials
	// Assumes you have access to the remote AWS docker registry
	// hint: runt aws ecr get-login --no-include-email
	//start = time.Now()
	//output, err = ins.InspectRemoteDockerImage("405074236871.dkr.ecr.eu-west-1.amazonaws.com/stavros-test/sensor-data-scala:90-d662d87-dirty")
	//elapsed = time.Since(start)
	//fmt.Printf("aws inspect took %s\n", elapsed)
	//assert.Equal(t, err, nil)
	//
	//
	// Assumes you have access to the remote GCloud registry
	// hint: https://cloud.google.com/container-registry/docs/advanced-authentication#gcloud-helper
	//start = time.Now()
	//output, err = ins.InspectRemoteDockerImage("eu.gcr.io/bubbly-observer-178213/spark-aggregation:134-d0ec286-dirty")
	//elapsed = time.Since(start)
	//fmt.Printf("gcloud inspect took %s\n", elapsed)
	//assert.Equal(t, err, nil)
	//
}
