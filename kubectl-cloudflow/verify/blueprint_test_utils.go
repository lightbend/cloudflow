package verify

import (
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/google/uuid"
	"strings"
)

func foo() string {
	return "debasish"
}

func buildStreamletDescriptor(className string, runtime string) StreamletDescriptor {
	return StreamletDescriptor{
		ClassName: className,
		Runtime:   runtime,
	}
}

func buildStreamletDescriptorFull(
	className string,
	runtime string,
	labels []string,
	description string,
	inlets []domain.InOutlet,
	outlets []domain.InOutlet,
	configParameters []domain.ConfigParameterDescriptor,
	attributes []domain.Attribute,
	volumeMounts []domain.VolumeMountDescriptor) StreamletDescriptor {
	return StreamletDescriptor{
		ClassName:        className,
		Runtime:          runtime,
		Labels:           labels,
		Description:      description,
		Inlets:           inlets,
		Outlets:          outlets,
		ConfigParameters: configParameters,
		Attributes:       attributes,
		VolumeMounts:     volumeMounts,
	}
}

func getRandomClassName() string {
	return strings.ReplaceAll(uuid.New().String(), "-", "")
}

func randomStreamlet() StreamletDescriptor {
	return buildStreamletDescriptor(getRandomClassName(), "akka")
}

func randomStreamletForRuntime(runtime string) StreamletDescriptor {
	return buildStreamletDescriptor(getRandomClassName(), runtime)
}

func streamlet(className string) StreamletDescriptor {
	return buildStreamletDescriptor(className, "akka")
}

func streamletForRuntime(className string, runtime string) StreamletDescriptor {
	return buildStreamletDescriptor(className, runtime)
}

// Transforms the descriptor into an ingress
func (s StreamletDescriptor) asIngress(outlets []domain.InOutlet) StreamletDescriptor {
	desc := StreamletDescriptor(s)
	desc.Outlets = outlets
	desc.Inlets = nil
	return desc
}