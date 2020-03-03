package verify

import (
	"github.com/go-akka/configuration"
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
	return "$" + strings.ReplaceAll(uuid.New().String(), "-", "")
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
func (s StreamletDescriptor) asIngress(outletName string, schemaName string, schema string) StreamletDescriptor {
	desc := StreamletDescriptor(s)
	var outlet = domain.InOutlet{Name:outletName, Schema: domain.InOutletSchema {
		Fingerprint: GetSHA256Hash(schema),
		Schema: schema,
		Name: schemaName,
		Format: avroFormat,
	}}
	desc.Outlets = []domain.InOutlet{outlet}
	desc.Inlets = nil
	return desc
}

// Transforms the descriptor into a Processor
func (s StreamletDescriptor) asProcessor(outletName string, outletSchemaName string, inletName string, inletSchemaName string, schema string) StreamletDescriptor {
	desc := StreamletDescriptor(s)
	var outlet = domain.InOutlet{Name:outletName, Schema: domain.InOutletSchema {
		Fingerprint: GetSHA256Hash(schema),
		Schema: schema,
		Name: outletSchemaName,
		Format: avroFormat,
	}}

	var inlet = domain.InOutlet{Name:inletName, Schema: domain.InOutletSchema {
		Fingerprint: GetSHA256Hash(schema),
		Schema: schema,
		Name: inletSchemaName,
		Format: avroFormat,
	}}
	desc.Outlets = []domain.InOutlet{outlet}
	desc.Inlets = []domain.InOutlet{inlet}
	return desc
}

func (s StreamletDescriptor) ref(refName string, metadata *configuration.Config) StreamletRef {
	return StreamletRef {
		name: refName,
		className: s.ClassName,
		metadata: metadata,
	}
}
