package verify

import (
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/stretchr/testify/assert"
	"testing"
)

func createBlueprintSample() string {
	return `blueprint {
		images {
			in = docker.io/lightbend/sensors-spark
            out = docker.io/lightbend/spensors-flink
		}
		streamlets {
            ingress = in/sensors.SparkRandomGenDataIngress
            process = in/sensors.MovingAverageSparklet
            egress = out/sensors.FlinkConsoleEgress
		}
		connections {
            ingress.out = [process.in]
            process.out = [egress.in]
		}
	}`
}


func Test_VerifyConnectionHash(t *testing.T) {
	vInlet := VerifiedInlet{
		VerifiedPort{
			"blabla",
			domain.InOutletSchema{
				"adadasdasda",
				"{\"type\":\"record\",\"name\":\"Data\",\"namespace\":\"sensors\",\"fields\":[{\"name\":\"src\",\"type\":\"string\"},{\"name\":\"timestamp\",\"type\":\"long\"},{\"name\":\"gauge\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}",
				"",
				"avro",
			},
		},
		VerifiedStreamlet{},
	}

	hash1 := GetSHA256Hash(vInlet)

	vEmptyInlet := VerifiedInlet{}
	hash2 := GetSHA256Hash(vEmptyInlet)
	assert.NotEqual(t, hash1, hash2)
}

func Test_VerifyFailIfBluperintIsEmpty(t *testing.T) {
	var blueprint= Blueprint{}.verify()
	empty := []BlueprintProblem{EmptyStreamlets{}, EmptyStreamletDescriptors{}}
	assert.ElementsMatch(t, blueprint.UpdateAllProblems(), empty)
}
