package verify

import (
	"gotest.tools/assert"
	"testing"
)

func Test_verifyStreamletRefWithNoProblems(t *testing.T) {
	var ref = getTestStreamletRef()
	var descriptors = []StreamletDescriptor{}
	descriptors =  append(descriptors, StreamletDescriptor{
		ClassName: "sensors.MovingAverageSparklet",
		Runtime: "spark",
	})
	assert.Equal(t, len(ref.verify(descriptors).problems),0 , "Number of problems should be zero")
}

func Test_verifyStreamletRefWithAmbiguityProblem(t *testing.T) {
	var ref = getTestStreamletRef()
	var descriptors = []StreamletDescriptor{}
	descriptors =  append(descriptors, StreamletDescriptor{
		ClassName: "test1.sensors.MovingAverageSparklet",
		Runtime: "spark",
	})
	descriptors =  append(descriptors, StreamletDescriptor{
		ClassName: "test.sensors.MovingAverageSparklet",
		Runtime: "spark",
	})

	assert.Equal(t, GetProblem(ref.verify(descriptors).problems, AmbiguousStreamletRef{
		AmbiguousStreamletRef{},
		ref.name,
		ref.className,}), true)
}

func Test_verifyStreamletRefForOverlappingClassnames(t *testing.T) {
	var ref = getTestStreamletRef()
	var descriptors = []StreamletDescriptor{}
	descriptors =  append(descriptors, StreamletDescriptor{
		ClassName: "sensors.MovingAverageSparkletA",
		Runtime: "spark",
	})

	descriptors =  append(descriptors, StreamletDescriptor{
		ClassName: "sensors.MovingAverageSparkletAB",
		Runtime: "spark",
	})

	var t1 = ref.verify(descriptors).problems
    var r = GetProblem(t1,AmbiguousStreamletRef{
		AmbiguousStreamletRef{},
			ref.name,
			ref.className,})
	assert.Equal(t, r, false)
}

func Test_verifyStreamletRefWithInvalidClassName(t *testing.T) {
	var ref = getTestStreamletRef()
	ref.className = "2sensors.MovingAverageSparklet"
	var descriptors = []StreamletDescriptor{}

	assert.Equal(t, GetProblem(ref.verify(descriptors).problems, InvalidStreamletClassName{
	streamletRef:	ref.name,
	streamletClassName: ref.className,}), true)
}

func Test_verifyStreamletRefWithInvalidName(t *testing.T) {
	var ref = getTestStreamletRef()
	ref.name = "@Spark"
	var descriptors = []StreamletDescriptor{}

	assert.Equal(t, GetProblem(ref.verify(descriptors).problems, InvalidStreamletName{
		streamletRef:ref.name,}), true)
}

func Test_verifyStreamletRefWithEmptyDescriptors(t *testing.T) {
	var ref = getTestStreamletRef()
	var descriptors = []StreamletDescriptor{}

	assert.Equal(t, GetProblem(ref.verify(descriptors).problems, StreamletDescriptorNotFound{
		StreamletDescriptorNotFound{},
		ref.name,
		ref.className,},), true)
}

func GetProblem(problems []BlueprintProblem, problemToFind BlueprintProblem) bool {
	for _, problem := range problems {
		if problem == problemToFind {
			return true
		}
	}
	return false
}

func getTestStreamletRef() StreamletRef {
	return StreamletRef {
		"spark",
		"sensors.MovingAverageSparklet",
		nil,
		nil,
		nil,
		nil,
	}
}
