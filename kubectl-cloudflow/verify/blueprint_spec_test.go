package verify

import (
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
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
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{InvalidStreamletName{streamletRef:name}}))
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
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{InvalidStreamletName{streamletRef:name}}))
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
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{InvalidStreamletClassName{streamletRef:ref.name, streamletClassName:className}}))
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
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{InvalidOutletName{className:ingress.ClassName, name: outletName}}))
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
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{InvalidInletName{className:processor.ClassName, name: inletName}}))
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
			Expect(connected.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{
				UnconnectedInlets{
					unconnectedInlets: []UnconnectedInlet{{streamletRef: "bar", inlet: merge.Inlets[1]}},
				},
			}))
		})
	})

	Context("by default", func() {
		It("fail verification for configuration parameters with invalid validation patterns", func() {
			var blueprint =  createBlueprintWithConfigurationParameter(
				[]domain.ConfigParameterDescriptor{{
					Key: "test-parameter",
					Description: "",
					Type: "string",
					Pattern: `^.{1,65535\K$`,
					DefaultValue: nil,
				}})

			var problems = blueprint.UpdateGlobalProblems()
			Expect(len(problems) > 0 ).Should(Equal(true))
			_, ok := blueprint.globalProblems[0].(InvalidValidationPatternConfigParameter)
			Expect(ok).Should(Equal(true))
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
