package verify

import (
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
			BlueprintProblem: InvalidStreamletName{},
				streamletRef: ret.name,
		})
	}

	if !CheckFullPatternMatch(ret.className, ClassNamePattern) {
		ret.problems = append(ret.problems, InvalidStreamletClassName{
			BlueprintProblem: InvalidStreamletClassName{},
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
		ret.verified = &VerifiedStreamlet{s.name, domain.Descriptor(*found)}
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
			ret.verified = &VerifiedStreamlet{s.name, domain.Descriptor(partialDesc[0])}
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
