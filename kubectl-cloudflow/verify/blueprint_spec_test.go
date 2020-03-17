package verify

import (
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
	"os"
	"reflect"
)


var FOO = `{
   "type":"record",
   "name":"FOO",
   "fields":[
      {
         "name":"first",
         "type":"string"
      }
   ]
}`

var BAR = `{
   "type":"record",
   "name":"BAR",
   "fields":[
      {
         "name":"first",
         "type":"string"
      }
   ]
}`

var _ = Describe("A blueprint", func() {
	Context("if streamlets, connections and streamlet descriptors are empty", func() {
		It("should fail verification", func() {
			var blueprint = Blueprint{}.verify()
			emptyProblems := []BlueprintProblem{EmptyStreamlets{}, EmptyStreamletDescriptors{}, EmptyImages{}}
			problems := blueprint.UpdateGlobalProblems()
			for _, p := range problems {
				println(reflect.TypeOf(p))
			}
			Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf(emptyProblems))
		})
	})

	Context("if it has no streamlets", func() {
		It("should fail verification", func() {
			var ingress = randomStreamlet()
			var processor = randomStreamlet()
			ingress = ingress.asIngress("out", "foo", FOO)
			processor = processor.asProcessor("out", "foo", "in", "foo", FOO, FOO)
			var blueprint = Blueprint{}
			blueprint = blueprint.define([]StreamletDescriptor{ingress, processor})
			problems := []BlueprintProblem{EmptyStreamlets{}}
			Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf(problems))

		})
	})

	Context("if its streamlet names are valid", func() {
		It("should not fail verification", func() {
			var names = []string{"a", "abcd", "a-b", "ab--cd", "1ab2", "1ab", "1-2"}
			for _, name := range names {
				var ingress = randomStreamlet()
				var blueprint = Blueprint{}
				blueprint = blueprint.define([]StreamletDescriptor{ingress}).use(ingress.ref(name, nil))
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{}))
			}
		})
	})

	Context("if its streamlet names are not valid", func() {
		It("should fail verification", func() {
			var names = []string{"A", "aBcd", "9B", "-ab", "ab-", "a_b", "a/b", "a+b"}
			for _, name := range names {
				var ingress = randomStreamlet()
				var blueprint = Blueprint{}
				blueprint = blueprint.define([]StreamletDescriptor{ingress}).use(ingress.ref(name, nil))
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf(InvalidStreamletName{streamletRef:name}))
			}
		})
	})

	Context("if its streamlet names are not valid", func() {
		It("should fail verification", func() {
			var names = []string{"A", "aBcd", "9B", "-ab", "ab-", "a_b", "a/b", "a+b"}
			for _, name := range names {
				var ingress = randomStreamlet()
				var blueprint = Blueprint{}
				blueprint = blueprint.define([]StreamletDescriptor{ingress}).use(ingress.ref(name, nil))
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf(InvalidStreamletName{streamletRef:name}))
			}
		})
	})

	Context("if its streamlets classnames are not valid", func() {
		It("should fail verification", func() {
			var classNames = []string{"-ab", "ab-", "1ab", "a/b", "a+b"}
			for _, className:= range classNames {
				var ingress = streamlet(className).asIngress("out", "foo", FOO)
				var blueprint = Blueprint{}
				var ref = ingress.randomRef()
				blueprint = blueprint.define([]StreamletDescriptor{ingress}).use(ref)
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf(InvalidStreamletClassName{streamletRef:ref.name, streamletClassName:className}))
			}
		})
	})

	Context("if it uses a streamlet with a valid outlet name", func() {
		It("should pass verification", func() {
			var outletNames = []string{"a", "abcd", "a-b", "ab--cd", "1ab2", "1ab", "1-2"}
			for _, outletName:= range outletNames {
				var ingress = randomStreamlet().asIngress(outletName, "foo", FOO)
				var blueprint = Blueprint{}
				var ref = ingress.randomRef()
				blueprint = blueprint.define([]StreamletDescriptor{ingress}).use(ref)
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{}))
			}
		})
	})

	Context("if it uses a streamlet with an invalid outlet name", func() {
		It("should fail verification", func() {
			var outletNames = []string{"A", "aBcd", "9B", "-ab", "ab-", "a_b", "a/b", "a+b"}
			for _, outletName:= range outletNames {
				var ingress = randomStreamlet().asIngress(outletName, "foo", FOO)
				var blueprint = Blueprint{}
				var ref = ingress.randomRef()
				blueprint = blueprint.define([]StreamletDescriptor{ingress}).use(ref)
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf(InvalidOutletName{className:ingress.ClassName, name: outletName}))
			}
		})
	})

	Context("if it uses a streamlet with a valid inlet name", func() {
		It("should pass verification", func() {
			var inletNames = []string{"a", "abcd", "a-b", "ab--cd", "1ab2", "1ab", "1-2"}
			for _, inletName:= range inletNames {
				var ingress = randomStreamlet()
				var processor = randomStreamlet()
				ingress = ingress.asIngress("out", "foo", FOO)
				processor = processor.asProcessor("out", "foo", inletName, "foo", FOO, FOO)
				var blueprint = Blueprint{}
				var ingressRef = ingress.ref("foo", nil)
				var processorRef = processor.ref("bar", nil)
				blueprint = blueprint.define([]StreamletDescriptor{ingress, processor}).use(ingressRef).use(processorRef).connect(ingressRef.out(), processorRef.inlet(inletName))
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{}))
			}
		})
	})

	Context("if it uses a streamlet with an invalid inlet name", func() {
		It("should pass verification", func() {
			var inletNames = []string{"A", "aBcd", "9B", "-ab", "ab-", "a_b", "a/b", "a+b"}
			for _, inletName:= range inletNames {
				var ingress = randomStreamlet()
				var processor = randomStreamlet()
				ingress = ingress.asIngress("out", "foo", FOO)
				processor = processor.asProcessor("out", "foo", inletName, "foo", FOO, FOO)
				var blueprint = Blueprint{}
				var ingressRef = ingress.ref("foo", nil)
				var processorRef = processor.ref("bar", nil)
				blueprint = blueprint.define([]StreamletDescriptor{ingress, processor}).use(ingressRef).use(processorRef).connect(ingressRef.out(), processorRef.inlet(inletName))
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf(InvalidInletName{className:processor.ClassName, name: inletName}))
			}
		})
	})

	Context("by default", func() {
		It("should be able to define and use streamlets", func() {
			var ingress = randomStreamlet()
			var processor = randomStreamlet()
			ingress = ingress.asIngress("out", "foo", FOO)
			processor = processor.asProcessor("out", "foo", "in", "foo", FOO, FOO)
			var blueprint = Blueprint{}
			var ingressRef = ingress.randomRef()
			blueprint = blueprint.define([]StreamletDescriptor{ingress, processor}).use(ingressRef)
			Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{}))
			Expect(blueprint.streamlets[0]).Should(Equal(
				StreamletRef{
					name: ingressRef.name,
					className: ingress.ClassName,
					verified: &VerifiedStreamlet{name: ingressRef.name, descriptor: ingress},
			}))
		})
	})
	
	Context("by default", func() {
		It("should be able to define, use and connect streamlets", func() {
			var ingress = randomStreamlet()
			var processor = randomStreamlet()
			ingress = ingress.asIngress("out", "foo", FOO)
			processor = processor.asProcessor("out", "foo", "in", "foo", FOO, FOO)
			var blueprint = Blueprint{}
			var ingressRef = ingress.ref("foo", nil)
			var processorRef = processor.ref("bar", nil)
			blueprint = blueprint.define([]StreamletDescriptor{ingress, processor}).use(ingressRef).use(processorRef).connect(ingressRef.out(), processorRef.in())
			Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{}))
			Expect(blueprint.streamlets[0]).Should(Equal(
				StreamletRef{
					name: ingressRef.name,
					className: ingress.ClassName,
					verified: &VerifiedStreamlet{name: ingressRef.name, descriptor: ingress},
				}))
			Expect(blueprint.streamlets[1]).Should(Equal(
				StreamletRef{
					name: processorRef.name,
					className: processor.ClassName,
					verified: &VerifiedStreamlet{name: processorRef.name, descriptor: processor},
				}))
		})
	})

	Context("by default", func() {
		It("should be able to define, use and connect streamlets", func() {
			var ingress = buildStreamletDescriptor("com.example.Foo", "akka").asIngress("out", "foo", FOO)
			var processor1 = buildStreamletDescriptor("com.example.Fooz", "akka").asProcessor("out", "foo", "in", "foo", FOO, FOO)
			var processor2 = buildStreamletDescriptor("com.acme.SnaFoo", "akka").asProcessor("out", "bar", "in", "foo", FOO, BAR)
			var processor3 = buildStreamletDescriptor("io.github.FooBar", "akka").asProcessor("out", "bar", "in", "bar", BAR, BAR)
			var blueprint = Blueprint{}
			var ingressRef = ingress.ref("foo", nil)
			var processor1Ref = processor1.ref("fooz", nil)
			var processor2Ref = processor2.ref("bar", nil)
			var processor3Ref = processor3.ref("foobar", nil)

			blueprint = blueprint.define([]StreamletDescriptor{ingress, processor1, processor2, processor3})

			blueprint= blueprint.use(ingressRef).use(ingressRef).use(processor1Ref).use(processor2Ref).use(processor3Ref)
			blueprint= blueprint.connect(ingressRef.out(), processor1Ref.in()).connect(processor1Ref.out(), processor2Ref.in()).connect(processor2Ref.out(), processor3Ref.in())
			Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{}))
		})
	})

	Context("by default", func() {
		It("should not allow connecting to a streamlet with more than one inlet using a short name", func() {
			var ingress = randomStreamlet()
			var merge = randomStreamlet()
			ingress = ingress.asIngress("out", "foo", FOO)
			merge = merge.asMerge(MergeInfo{
				inletName0: "in-0",
				inletName1: "in-1",
				outletName: "out",
				inletSchemaName0: "foo",
				inletSchemaName1: "bar",
				outletSchemaName: "foo",
				inletSchema0: FOO,
				inletSchema1: BAR,
				outletSchema: FOO,

			})
			var blueprint = connectedBlueprint([]StreamletDescriptor{ingress, merge})
			var ingressRef = blueprint.streamlets[0]
			var mergeRef = blueprint.streamlets[1]
			var b = blueprint.connectWithConnection(StreamletConnection{from: ingressRef.name, to: mergeRef.name})
			b.UpdateGlobalProblems()
			Expect(len(b.UpdateGlobalProblems())).Should(Equal(2))
		})
	})


	Context("by default", func() {
		It("should be able to connect to the correct inlet using a full port path when the streamlet has more than one inlet", func() {
			var ingress = randomStreamlet()
			var merge = randomStreamlet()
			ingress = ingress.asIngress("out", "foo", FOO)
			merge = merge.asMerge(MergeInfo{
				inletName0: "in-0",
				inletName1: "in-1",
				outletName: "out",
				inletSchemaName0: "foo",
				inletSchemaName1: "bar",
				outletSchemaName: "foo",
				inletSchema0: FOO,
				inletSchema1: BAR,
				outletSchema: FOO,

			})
			var ingressRef = ingress.ref("foo", nil)
			var mergeRef   = merge.ref("bar", nil)
			var blueprint = Blueprint{}
			blueprint = blueprint.define([]StreamletDescriptor{ingress, merge}).use(ingressRef).use(mergeRef)
			var connected = blueprint.connect(ingressRef.name, mergeRef.in0())

			Expect(len(connected.UpdateGlobalProblems())).Should(Equal(1))
			Expect(connected.UpdateGlobalProblems()).Should(ConsistOf(
				UnconnectedInlets{
					unconnectedInlets: []UnconnectedInlet{{streamletRef: "bar", inlet: merge.Inlets[1]}},
				},
			))
		})
	})

	Context("by default", func() {
		It("not fail verification with UnconnectedInlets for already reported IllegalConnection and IncompatibleSchema problems", func() {
			var ingress = randomStreamlet()
			var processor = randomStreamlet()
			var egress = randomStreamlet()
			ingress = ingress.asIngress("out", "foo", FOO)
			processor = processor.asProcessor("out", "bar", "in", "foo", FOO, BAR)
			pattern := "^1,65535$"
			egress = egress.asEgress("in", "bar", BAR).withConfigParameters(
				[]domain.ConfigParameterDescriptor{{
					Key: "target-uri",
					Description: "",
					Type: "string",
					Pattern: &pattern,
					DefaultValue: nil,
				}})
			var blueprint = Blueprint{}
			var ingressRef    = ingress.randomRef()
			var processor1Ref = processor.randomRef()
			var processor2Ref = processor.randomRef()
			var egress1Ref    = egress.randomRef()
			var egress2Ref    = egress.randomRef()
			blueprint = blueprint.define([]StreamletDescriptor{ingress, processor, egress}).use(ingressRef).use(processor1Ref).use(processor2Ref).use(egress1Ref).use(egress2Ref)
			blueprint = blueprint.connect(ingressRef.out(), processor1Ref.in()).connect(ingressRef.out(), processor2Ref.in()).connect(processor1Ref.out(), egress1Ref.in())
			blueprint = blueprint.connect(processor2Ref.out(), egress1Ref.in()).connect(ingressRef.out(), egress2Ref.in())
			blueprint = blueprint.upsertStreamletRef(egress1Ref.name, nil, nil).upsertStreamletRef(egress2Ref.name, nil, nil)
			problems := blueprint.UpdateGlobalProblems()
			outPortPath := "out"
			inPortPath := "in"
			Expect(problems).Should(ConsistOf(
				IllegalConnection{
					outletPaths: []VerifiedPortPath{
						{streamletRef:processor1Ref.name,  portName: &outPortPath},
						{streamletRef:processor2Ref.name, portName: &outPortPath},
				},
				inletPath: VerifiedPortPath{streamletRef:egress1Ref.name, portName: &inPortPath},
				},
				IncompatibleSchema{
					outletPortPath: VerifiedPortPath{streamletRef:ingressRef.name,  portName: &outPortPath},
					inletPath: VerifiedPortPath{streamletRef:egress2Ref.name,  portName: &inPortPath},
				}))

		})
	})

	Context("by default", func() {
		It("should fail verification for configuration parameters with invalid validation patterns", func() {
			pattern := `^.{1,65535\K$`
			var blueprint =  createBlueprintWithConfigurationParameter(
				[]domain.ConfigParameterDescriptor{{
					Key: "test-parameter",
					Description: "",
					Type: "string",
					Pattern: &pattern,
					DefaultValue: nil,
				}})

			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			_, ok := blueprint.globalProblems[0].(InvalidValidationPatternConfigParameter)
			Expect(ok).Should(Equal(true))
		})
	})

	Context("by default", func() {
		It("should fail verification for configuration parameters with invalid default regexp value", func() {
			defaultValue := "invalid-default-value"
			pattern := `^debug|info|warning|error$`
			var blueprint =  createBlueprintWithConfigurationParameter(
				[]domain.ConfigParameterDescriptor{{
					Key: "log-level",
					Description: "Provide one of the following log levels, debug,info, warning or error",
					Type: "string",
					Pattern: &pattern,
					DefaultValue: &defaultValue,
				}})

			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			_, ok := blueprint.globalProblems[0].(InvalidDefaultValueInConfigParameter)
			Expect(ok).Should(Equal(true))
		})
	})

	Context("by default", func() {
		It("should fail verification for configuration parameters with invalid default duration", func() {
			defaultValue := "20 parsec"
			var blueprint =  createBlueprintWithConfigurationParameter(
				[]domain.ConfigParameterDescriptor{{
					Key: "duration-value",
					Description: "Provide a duration of time",
					Type: "duration",
					Pattern: nil,
					DefaultValue: &defaultValue,
				}})

			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			_, ok := blueprint.globalProblems[0].(InvalidDefaultValueInConfigParameter)
			Expect(ok).Should(Equal(true))
		})
	})

	Context("by default", func() {
		It("should be able to validate a correct duration in a default value", func() {
			defaultValue := "1 minute"
			var blueprint =  createBlueprintWithConfigurationParameter(
				[]domain.ConfigParameterDescriptor{{
					Key: "duration-value",
					Description: "Provide a duration of time",
					Type: "duration",
					Pattern: nil,
					DefaultValue: &defaultValue,
				}})

			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) == 0 ).Should(Equal(true))
		})
	})

	Context("by default", func() {
		It("should be able to validate a correct memory size in a default value", func() {
			defaultValue := "20 M"
			var blueprint =  createBlueprintWithConfigurationParameter(
				[]domain.ConfigParameterDescriptor{{
					Key: "memorysize-value",
					Description: "Provide a memory size",
					Type: "memorysize",
					Pattern: nil,
					DefaultValue: &defaultValue,
				}})

			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) == 0 ).Should(Equal(true))
		})
	})

	Context("by default", func() {
		It("should fail verification for configuration parameters with invalid default memory size", func() {
			defaultValue := "42 pigeons"
			var blueprint =  createBlueprintWithConfigurationParameter(
				[]domain.ConfigParameterDescriptor{{
					Key: "memorysize-value",
					Description: "Provide a memory size",
					Type: "memorysize",
					Pattern: nil,
					DefaultValue: &defaultValue,
				}})

			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			_, ok := blueprint.globalProblems[0].(InvalidDefaultValueInConfigParameter)
			Expect(ok).Should(Equal(true))
		})
	})

	Context("by default", func() {
		It("should fail verification for configuration parameters with duplicate keys", func() {
			defaultValue1 := "42m"
			defaultValue2 := "52m"
			var blueprint =  createBlueprintWithConfigurationParameter(
				[]domain.ConfigParameterDescriptor{{
					Key: "memorysize-value",
					Description: "Provide a memory size",
					Type: "memorysize",
					Pattern: nil,
					DefaultValue: &defaultValue1,
				},
				{
					Key: "memorysize-value",
					Description: "Another memory size parameter with a duplicate name",
					Type: "memorysize",
					Pattern: nil,
					DefaultValue: &defaultValue2,
				},
				})

			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			_, ok := blueprint.globalProblems[0].(DuplicateConfigParameterKeyFound)
			Expect(ok).Should(Equal(true))
		})
	})

	Context("by default", func() {
		It("should fail verification for volume mounts with duplicate names", func() {
			var blueprint =  createBlueprintWithVolumeMounts(
				[]domain.VolumeMountDescriptor{{
					Name: "ml-data",
					Path: string(os.PathSeparator) + "some-path",
					AccessMode: "ReadWriteMany",
				},
				{
					Name: "ml-data",
					Path: string(os.PathSeparator) + "some-other-path",
					AccessMode: "ReadWriteMany",
				},
			})
			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			value, ok := blueprint.globalProblems[0].(DuplicateVolumeMountName)
			Expect(ok).Should(Equal(true))
			Expect(value.name).Should(Equal("ml-data"))
		})
	})

	Context("by default", func() {
		It("should fail verification for volume mounts with duplicate paths", func() {
			var blueprint =  createBlueprintWithVolumeMounts(
				[]domain.VolumeMountDescriptor{{
					Name: "ml-data",
					Path: string(os.PathSeparator) + "some-path",
					AccessMode: "ReadWriteMany",
				},
					{
						Name: "other-ml-data",
						Path: string(os.PathSeparator) + "some-path",
						AccessMode: "ReadWriteMany",
					},
				})
			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			value, ok := blueprint.globalProblems[0].(DuplicateVolumeMountPath)
			Expect(ok).Should(Equal(true))
			Expect(value.path).Should(Equal(string(os.PathSeparator) + "some-path"))
		})
	})

	Context("by default", func() {
		It("should fail verification for volume mounts with invalid names", func() {
			var firstBlueprint =  createBlueprintWithVolumeMounts(
				[]domain.VolumeMountDescriptor{{
					Name: "-ml-data",
					Path: string(os.PathSeparator) + "some-path",
					AccessMode: "ReadWriteMany",
				}})
			var problems = firstBlueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			value, ok := firstBlueprint.globalProblems[0].(InvalidVolumeMountName)
			Expect(ok).Should(Equal(true))
			Expect(value.name).Should(Equal("-ml-data"))
			var secondBlueprint =  createBlueprintWithVolumeMounts(
				[]domain.VolumeMountDescriptor{{
					Name: "a-string-longer-than-63-characters---------------------------------------------------------------------------------------",
					Path: string(os.PathSeparator) + "some-path",
					AccessMode: "ReadWriteMany",
				}})
			problems = secondBlueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			value, ok = secondBlueprint.globalProblems[0].(InvalidVolumeMountName)
			Expect(ok).Should(Equal(true))
			Expect(value.name).Should(Equal("a-string-longer-than-63-characters---------------------------------------------------------------------------------------"))
		})
	})

	Context("by default", func() {
		It("should fail verification for volume mounts with invalid paths", func() {
			var blueprint =  createBlueprintWithVolumeMounts(
				[]domain.VolumeMountDescriptor{{
					Name: "ml-data",
					Path: ".." + string(os.PathSeparator) + "some-path",
					AccessMode: "ReadWriteMany",
				}})
			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			value, ok := blueprint.globalProblems[0].(BacktrackingVolumeMounthPath)
			Expect(ok).Should(Equal(true))
			Expect(value.name).Should(Equal("ml-data"))
		})
	})

	Context("by default", func() {
		It("should fail verification for volume mounts with non-absolute paths", func() {
			var blueprint =  createBlueprintWithVolumeMounts(
				[]domain.VolumeMountDescriptor{{
					Name: "ml-data",
					Path: "some-path" + string(os.PathSeparator) + "testing",
					AccessMode: "ReadWriteMany",
				}})
			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			value, ok := blueprint.globalProblems[0].(NonAbsoluteVolumeMountPath)
			Expect(ok).Should(Equal(true))
			Expect(value.name).Should(Equal("ml-data"))
		})
	})

	Context("by default", func() {
		It("should fail verification for volume mounts with empty paths", func() {
			var blueprint =  createBlueprintWithVolumeMounts(
				[]domain.VolumeMountDescriptor{{
					Name: "ml-data",
					AccessMode: "ReadWriteMany",
				}})
			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			value, ok := blueprint.globalProblems[0].(EmptyVolumeMountPath)
			Expect(ok).Should(Equal(true))
			Expect(value.name).Should(Equal("ml-data"))
		})
	})

	Context("by default", func() {
		It("should validate correctly a correct volume mount", func() {
			var blueprint =  createBlueprintWithVolumeMounts(
				[]domain.VolumeMountDescriptor{{
					Name: "ml-data",
					Path: string(os.PathSeparator) + "some-path",
					AccessMode: "ReadWriteMany",
				}})
			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) == 0 ).Should(Equal(true))
		})
	})
})

func createBlueprintWithConfigurationParameter(parameters []domain.ConfigParameterDescriptor) Blueprint {
	var ingress = randomStreamlet()
	var processor = randomStreamlet()
	ingress = ingress.asIngress("out", "foo", FOO)
	processor = processor.asProcessor("out", "foo", "in", "foo", FOO, FOO).withConfigParameters(parameters)
	return connectedBlueprint([]StreamletDescriptor{ingress, processor})
}

func createBlueprintWithVolumeMounts(volumeMounts []domain.VolumeMountDescriptor) Blueprint {
	var ingress = randomStreamlet()
	var processor = randomStreamlet()
	ingress = ingress.asIngress("out", "foo", FOO)
	processor = processor.asProcessor("out", "foo", "in", "foo", FOO, FOO).withVolumeMounts(volumeMounts)
	return connectedBlueprint([]StreamletDescriptor{ingress, processor})
}

