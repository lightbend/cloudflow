package verify

import (
	"github.com/go-akka/configuration"
	"github.com/google/uuid"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"strings"
)

func buildStreamletDescriptor(className string, runtime string) StreamletDescriptor {
	return StreamletDescriptor{
		ClassName: className,
		Runtime:   runtime,
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
func (s StreamletDescriptor) asProcessor(outletName string, outletSchemaName string, inletName string, inletSchemaName string, inletSchema string, outletSchema string) StreamletDescriptor {
	desc := StreamletDescriptor(s)
	var outlet = domain.InOutlet{Name:outletName, Schema: domain.InOutletSchema {
		Fingerprint: GetSHA256Hash(inletSchema),
		Schema:      outletSchema,
		Name:        outletSchemaName,
		Format:      avroFormat,
	}}

	var inlet = domain.InOutlet{Name:inletName, Schema: domain.InOutletSchema {
		Fingerprint: GetSHA256Hash(inletSchema),
		Schema:      inletSchema,
		Name:        inletSchemaName,
		Format:      avroFormat,
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

func (s StreamletDescriptor) withConfigParameters(parameters []domain.ConfigParameterDescriptor) StreamletDescriptor {
	return StreamletDescriptor{
		Attributes: s.Attributes,
		ClassName: s.ClassName,
		ConfigParameters: parameters,
		VolumeMounts: s.VolumeMounts,
		Labels: s.Labels,
		Runtime: s.Runtime,
		Description: s.Description,
		Outlets: s.Outlets,
		Inlets: s.Inlets,
	}
}

type MergeInfo struct {
	inletName0 string
	inletName1 string
	outletName string
	inletSchemaName0 string
	inletSchemaName1 string
	outletSchemaName string
	inletSchema0 string
	inletSchema1 string
	outletSchema string
}

func (s StreamletDescriptor) asMerge(info MergeInfo) StreamletDescriptor {
	return StreamletDescriptor{
		Attributes: s.Attributes,
		ClassName: s.ClassName,
		ConfigParameters: s.ConfigParameters,
		VolumeMounts: s.VolumeMounts,
		Labels: s.Labels,
		Runtime: s.Runtime,
		Description: s.Description,
		Outlets: []domain.InOutlet{{
			Name: info.outletName, Schema: domain.InOutletSchema{
			Fingerprint: GetSHA256Hash(info.outletSchema),
			Schema:      info.outletSchema,
			Name:        info.outletSchema,
			Format:      avroFormat,
		}}},
		Inlets: []domain.InOutlet{{
			Name: info.inletName0, Schema: domain.InOutletSchema{
				Fingerprint: GetSHA256Hash(info.inletSchema0),
				Schema:      info.inletSchema0,
				Name:        info.inletSchemaName0,
				Format:      avroFormat},
		},
		{
			Name: info.inletName1, Schema: domain.InOutletSchema{
			Fingerprint: GetSHA256Hash(info.inletSchema1),
			Schema:      info.inletSchema1,
			Name:        info.inletSchemaName1,
			Format:      avroFormat,
		}}},
	}
}

func randomRefName () string {
	return "i" + strings.ReplaceAll(uuid.New().String(), "-", "")
}

func (s StreamletDescriptor) randomRef() StreamletRef {
	return s.ref(randomRefName(), nil)
}
