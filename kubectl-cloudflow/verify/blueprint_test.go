// +build integration

package verify

import (
	"testing"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
	"github.com/stretchr/testify/assert"
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

func createValidBlueprintSample() string {
	return `blueprint {
		name = call-record-aggregator
		images {
			aggr = "lightbend/spark-aggregation:134-d0ec286-dirty"
			ings = "lightbend/akka-cdr-ingestor:134-d0ec286-dirty"
			outp = "lightbend/akka-java-aggregation-output:134-d0ec286-dirty"
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

func createBlueprintWithoutImagesSample() string {
	return `blueprint {
		name = call-record-aggregator
		images {
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

func createBlueprintWithoutStreamletsSample() string {
	return `blueprint {
		name = call-record-aggregator
		images {
			aggr = "lightbend/spark-aggregation:134-d0ec286-dirty"
			ings = "lightbend/akka-cdr-ingestor:134-d0ec286-dirty"
			outp = "lightbend/akka-java-aggregation-output:134-d0ec286-dirty"
		}
		streamlets {

		}
		connections {
		}
	}`
}

func createBlueprintWithoutConnectionsSample() string {
	return `blueprint {
		name = call-record-aggregator
		images {
			aggr = "lightbend/spark-aggregation:134-d0ec286-dirty"
			ings = "lightbend/akka-cdr-ingestor:134-d0ec286-dirty"
			outp = "lightbend/akka-java-aggregation-output:134-d0ec286-dirty"
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
		}
	}`
}

func createBlueprintSampleWithMissingImage() string {
	return `blueprint {
		name = call-record-aggregator
		images {
			ings = "lightbend/akka-cdr-ingestor:134-d0ec286-dirty"
			outp = "lightbend/akka-java-aggregation-output:134-d0ec286-dirty"
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

func createBlueprintSampleWithImageInStreamletNotPresentInImages() string {
	return `blueprint {
		name = call-record-aggregator
		images {
			aggr = "lightbend/spark-aggregation:134-d0ec286-dirty"
			ings = "lightbend/akka-cdr-ingestor:134-d0ec286-dirty"
			outp = "lightbend/akka-java-aggregation-output:134-d0ec286-dirty"
		}
		streamlets {
			cdr-generator1 = notInImage/carly.aggregator.CallRecordGeneratorIngress
			cdr-generator2 = notInImage/carly.aggregator.CallRecordGeneratorIngress
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

func createBlueprintSampleWithConnectionsHavingInvalidStreamlets() string {
	return `blueprint {
		name = call-record-aggregator
		images {
			aggr = "lightbend/spark-aggregation:134-d0ec286-dirty"
			ings = "lightbend/akka-cdr-ingestor:134-d0ec286-dirty"
			outp = "lightbend/akka-java-aggregation-output:134-d0ec286-dirty"
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
			generator1.out = [merge.in-0]
			generator2.out = [merge.in-1]
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
			cloudflowapplication.InOutletSchema{
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

func Test_VerifyEmptyBlueprint(t *testing.T) {
	_, _, _, errors := VerifyBlueprint("")
	assert.NotEmpty(t, errors)
}

func Test_VerifyValidBlueprint(t *testing.T) {
	_, _, _, errors := VerifyBlueprint(createValidBlueprintSample())
	assert.Empty(t, errors)
}

func Test_VerifyBlueprintWithoutImages(t *testing.T) {
	_, _, _, errors := VerifyBlueprint(createBlueprintWithoutImagesSample())
	assert.NotEmpty(t, errors)
}

func Test_VerifyBlueprintWithoutStreamlets(t *testing.T) {
	_, _, _, errors := VerifyBlueprint(createBlueprintWithoutStreamletsSample())
	assert.NotEmpty(t, errors)
}

func Test_VerifyBlueprintWithoutConnections(t *testing.T) {
	_, _, _, errors := VerifyBlueprint(createBlueprintWithoutConnectionsSample())
	assert.NotEmpty(t, errors)
}

func Test_VerifyBlueprintWithMissingImage(t *testing.T) {
	_, _, _, errors := VerifyBlueprint(createBlueprintSampleWithMissingImage())
	assert.NotEmpty(t, errors)
}

func Test_VerifyBlueprintWithImageInStreamletNotPresentInImages(t *testing.T) {
	_, _, _, errors := VerifyBlueprint(createBlueprintSampleWithImageInStreamletNotPresentInImages())
	assert.NotEmpty(t, errors)
}

func Test_VerifyBlueprintWithConnectionsHavingInvalidStreamlets(t *testing.T) {
	_, _, _, errors := VerifyBlueprint(createBlueprintSampleWithConnectionsHavingInvalidStreamlets())
	assert.NotEmpty(t, errors)
}
