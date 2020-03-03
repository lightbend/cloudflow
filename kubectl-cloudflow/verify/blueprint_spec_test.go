package verify

import (
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

	Context("if no streamlets are used", func() {
		It("should fail verification", func() {
			var ingress = randomStreamlet()
			var processor = randomStreamlet()
			ingress = ingress.asIngress("out", "foo", FOO)
			processor = processor.asProcessor("out", "foo", "in", "foo", FOO)
			var blueprint = Blueprint{}
			blueprint = blueprint.define([]StreamletDescriptor{ingress, processor})
			problems := []BlueprintProblem{EmptyStreamlets{}}
			Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf(problems))

		})
	})

	Context("If the streamlet names are valid", func() {
		It("verification should not fail", func() {
			var names = []string{"a", "abcd", "a-b", "ab--cd", "1ab2", "1ab", "1-2"}
			for _, name := range names {
				var ingress = randomStreamlet()
				var blueprint = Blueprint{}
				blueprint = blueprint.define([]StreamletDescriptor{ingress})
				var t = ingress.ref(name, nil)
				blueprint = blueprint.use(t)
				Expect(blueprint.UpdateGlobalProblems()).Should(ConsistOf([]BlueprintProblem{}))
			}
		})
	})
})


