package verify

import (
	"github.com/go-akka/configuration"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowavro"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/util"
)

// StreamletConnection models the connections in a blueprint
type StreamletConnection struct {
	from     string
	to       string
	label    *string
	problems []BlueprintProblem
	verified *VerifiedStreamletConnection
	metadata *configuration.Config
}

const avroFormat string = "avro"

func (c StreamletConnection) verify(verifiedStreamlets []VerifiedStreamlet) StreamletConnection {
	_, fromPathError := NewVerifiedPortPath(c.from)
	_, toPathError := NewVerifiedPortPath(c.to)

	/*
		for _, vs := range verifiedStreamlets {
			fmt.Printf("%s\n", vs.name)
		}
	*/
	var patternErrors []BlueprintProblem
	var conErrors []BlueprintProblem

	if fromPathError != nil {
		patternErrors = append(patternErrors, fromPathError)
	}

	if toPathError != nil {
		patternErrors = append(patternErrors, toPathError)
	}

	outletResult, outletError := FindVerifiedOutlet(verifiedStreamlets, c.from)
	inletResult, inletError := FindVerifiedInlet(verifiedStreamlets, c.to)

	var verifiedFromPath *string
	var verifiedToPath *string

	if outletError != nil {
		verifiedFromPath = &c.from
		conErrors = append(conErrors, outletError)
	} else {
		portPath := outletResult.portPath().ToString()
		verifiedFromPath = &portPath
	}

	if inletError != nil {
		verifiedToPath = &c.to
		conErrors = append(conErrors, inletError)
	} else {
		portPath := inletResult.portPath().ToString()
		verifiedToPath = &portPath
	}

	var verConnection *VerifiedStreamletConnection
	if outletResult != nil && inletResult != nil {
		var label = ""
		if c.label == nil {
			label = ""
		} else {
			label = *(c.label)
		}
		verConnectionRes, schemaError := c.verifySchema(*outletResult, *inletResult, label)
		verConnection = verConnectionRes
		if schemaError != nil {
			conErrors = append(conErrors, schemaError)
		}
	}

	return StreamletConnection{
		problems: append(patternErrors, conErrors...),
		verified: verConnection,
		from:     *verifiedFromPath,
		to:       *verifiedToPath,
	}
}

func (c StreamletConnection) verifySchema(verifiedOutlet VerifiedOutlet, verifiedInlet VerifiedInlet, label string) (*VerifiedStreamletConnection, BlueprintProblem) {
	if verifiedOutlet.schemaDescriptor.Format == verifiedInlet.schemaDescriptor.Format &&
		verifiedOutlet.schemaDescriptor.Fingerprint == verifiedInlet.schemaDescriptor.Fingerprint {
		return &VerifiedStreamletConnection{verifiedOutlet: verifiedOutlet, verifiedInlet: verifiedInlet, label: &label}, nil
	} else if verifiedOutlet.schemaDescriptor.Format == avroFormat {
		err := cloudflowavro.CheckSchemaCompatibility(verifiedOutlet.schemaDescriptor.Schema, verifiedInlet.schemaDescriptor.Schema)

		rerr, ok := err.(*cloudflowavro.ReaderSchemaValidationError)

		if ok {
			util.LogAndExit("Inlet schema %s with fingerprint %s is not valid. Error: %s", verifiedInlet.schemaDescriptor.Name, verifiedOutlet.schemaDescriptor.Fingerprint, rerr.Error())
		}

		werr, ok := err.(*cloudflowavro.WriterSchemaValidationError)

		if ok {
			util.LogAndExit("Outlet schema %s with fingerprint %s is not valid. Error: %s", verifiedOutlet.schemaDescriptor.Name, verifiedOutlet.schemaDescriptor.Fingerprint, werr.Error())
		}

		if err != nil {
			return nil, &IncompatibleSchema{outletPortPath: verifiedOutlet.portPath(), inletPath: verifiedInlet.portPath()}
		} else {
			return &VerifiedStreamletConnection{verifiedOutlet: verifiedOutlet, verifiedInlet: verifiedInlet, label: &label}, nil
		}

	} else {
		return nil, &IncompatibleSchema{outletPortPath: verifiedOutlet.portPath(), inletPath: verifiedInlet.portPath()}
	}
}
