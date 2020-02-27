package verify

import (
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/stretchr/testify/assert"
	"reflect"
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

func createBlueprintSample1() string {
	return `blueprint {
		name = call-record-aggregator
		images {
			aggr = "eu.gcr.io/bubbly-observer-178213/spark-aggregation:134-d0ec286-dirty"
			ings = "eu.gcr.io/bubbly-observer-178213/akka-cdr-ingestor:134-d0ec286-dirty"
			outp = "eu.gcr.io/bubbly-observer-178213/akka-java-aggregation-output:134-d0ec286-dirty"
		}
		streamlets {
			cdr-generator1 = aggr/carly.aggregator.CallRecordGeneratorIngress
			cdr-generator2 = aggr/carly.aggregator.CallRecordGeneratorIngress
			merge = ings/carly.ingestor.CallRecordMerge
			cdr-ingress = ings/carly.ingestor.CallRecordIngress
			cdr-aggregator = aggr/carly.aggregator.CallStatsAggregator
			console-egress = outp/carly.output.AggregateRecordEgress
			error-egress = outp/carly.output.InvalidRecordEgress
	
		}
		connections {
			cdr-generator1.out = [merge.in-0]
			cdr-generator2.out = [merge.in-1]
			cdr-ingress.out = [merge.in-2]
			merge.valid = [cdr-aggregator.in]
			merge.invalid = [error-egress.in]
			cdr-aggregator.out = [console-egress.in]
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
	var blueprint = Blueprint{}.verify()
	empty := []BlueprintProblem{EmptyStreamlets{}, EmptyStreamletDescriptors{}, EmptyImages{}}
	problems := blueprint.UpdateAllProblems()
	for _, p := range problems {
		t.Log(reflect.TypeOf(p))
	}
	assert.ElementsMatch(t, blueprint.UpdateAllProblems(), empty)
}

func Test_VerifyValidBlueprint(t *testing.T) {
	errors := VerifyBlueprint(createBlueprintSample1())
	assert.Empty(t, errors)
}