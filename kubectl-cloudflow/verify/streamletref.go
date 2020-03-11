package verify

import (
	"fmt"
	"github.com/go-akka/configuration"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"strings"
)

// StreamletRef models each element in the streamlets section of a blueprint
type StreamletRef struct {
	name string
	className string
	problems []BlueprintProblem
	verified* VerifiedStreamlet
	metadata* configuration.Config
	imageId *string
}

// StreamletDescriptor is just a better domain element typedef
type StreamletDescriptor domain.Descriptor

func (s* StreamletRef) verify (streamletDescriptors []StreamletDescriptor) StreamletRef {
	ret := *s

	if !IsDnsLabelCompatible(s.name) {
		ret.problems = append(ret.problems, InvalidStreamletName{
				streamletRef: ret.name,
		})
	}

	if !CheckFullPatternMatch(ret.className, ClassNamePattern) {
		ret.problems = append(ret.problems, InvalidStreamletClassName{
			streamletRef: ret.name,
			streamletClassName: ret.className,
		})
	}

	var found *StreamletDescriptor
	for _, desc := range streamletDescriptors {

		if desc.ClassName == s.className {
			found = &desc
			break
		}
	}

	if found != nil {
		if found.ClassName != "" {
			ret.className = found.ClassName
		}
		ret.verified = &VerifiedStreamlet{s.name, *found}
	} else {
		matchingPartially := 0
		var partialDesc []StreamletDescriptor
		for _, desc := range streamletDescriptors {
			if strings.Contains(desc.ClassName, s.className) {
				matchingPartially = matchingPartially + 1
				partialDesc =  append(partialDesc, desc)
			}
		}

		if matchingPartially == 1 && partialDesc != nil {
			ret.verified = &VerifiedStreamlet{s.name, partialDesc[0]}
		} else if matchingPartially > 1 {
			ret.problems = append(ret.problems, AmbiguousStreamletRef{
				BlueprintProblem: AmbiguousStreamletRef{},
				streamletRef: s.name,
				streamletClassName: s.className,
			})
		} else {
			ret.problems = append(ret.problems, StreamletDescriptorNotFound{
				BlueprintProblem: StreamletDescriptorNotFound{},
				streamletRef: s.name,
				streamletClassName: s.className,
			})
		}
	}

	return ret
}

func (s StreamletRef) out() string {
	return fmt.Sprintf("%s.out", s.name)
}

func (s StreamletRef) in() string {
	return fmt.Sprintf("%s.in", s.name)
}

func (s StreamletRef) inlet(name string) string {
	return fmt.Sprintf("%s.%s", s.name, name)
}


//def createInletDescriptor[T: ClassTag: SchemaFor](name: String, schemaName: String) = {
//InletDescriptor(name, createSchemaDescriptor(schemaName))
//}
//
//def createOutletDescriptor[T: ClassTag: SchemaFor](name: String, schemaName: String) = {
//OutletDescriptor(name, createSchemaDescriptor(schemaName))
//}
