package verify

import (
	"fmt"
	"github.com/go-akka/configuration"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
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
type StreamletDescriptor cloudflowapplication.Descriptor

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

			var classParts = strings.Split(desc.ClassName, ".")
			classLastPart := classParts[len(classParts)-1]
			var sClassParts = strings.Split(s.className, ".")
			sClassLastPart := sClassParts[len(sClassParts)-1]

			if len(classParts) > 1 {
				if len(sClassParts) > 1 {
					classPartsPrefix := strings.Join(classParts[0:len(classParts)-1], ".")
					sClassPartsPrefix := strings.Join(sClassParts[0:len(sClassParts)-1], ".")

					if strings.Contains(classPartsPrefix, sClassPartsPrefix) && classLastPart == sClassLastPart {
						matchingPartially = matchingPartially + 1
						partialDesc =  append(partialDesc, desc)
					}
				} else {
					if  classLastPart == sClassLastPart {
						matchingPartially = matchingPartially + 1
						partialDesc =  append(partialDesc, desc)
					}
				}

			} else {
				if  classLastPart == sClassLastPart {
					matchingPartially = matchingPartially + 1
					partialDesc =  append(partialDesc, desc)
				}
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

func (s StreamletRef) in0() string {
	return fmt.Sprintf("%s.in-0", s.name)
}
