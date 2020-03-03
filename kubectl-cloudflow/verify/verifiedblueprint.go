package verify

import (
	"fmt"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
)

// SchemaDescriptor is a domain expressive typedef
type SchemaDescriptor domain.InOutletSchema

// OutletDescriptor is a domain expressive typedef
type OutletDescriptor domain.InOutlet

// InletDescriptor is a domain expressive typedef
type InletDescriptor domain.InOutlet

type VerifiedStreamlet struct {
	name       string
	descriptor domain.Descriptor
}

type VerifiedPortPath struct {
	streamletRef string
	portName     *string
}

type VerifiedPort struct {
	portName         string
	schemaDescriptor domain.InOutletSchema
}

type VerifiedInlet struct {
	VerifiedPort
	streamlet VerifiedStreamlet
}

type VerifiedOutlet struct {
	VerifiedPort
	streamlet VerifiedStreamlet
}

type VerifiedStreamletConnection struct {
	verifiedOutlet VerifiedOutlet
	verifiedInlet  VerifiedInlet
	label          *string
}

type VerifiedBlueprint struct {
	streamlets  []VerifiedStreamlet
	connections []VerifiedStreamletConnection
}

func (v *VerifiedStreamlet) outlet(outlet OutletDescriptor) VerifiedOutlet {
	return VerifiedOutlet{
		VerifiedPort{
			outlet.Name,
			outlet.Schema,
		},
		*v,
	}
}

func (v *VerifiedStreamlet) inlet(inlet InletDescriptor) VerifiedOutlet {
	return VerifiedOutlet{
		VerifiedPort{
			inlet.Name,
			inlet.Schema,
		},
		*v,
	}
}

// ToString string representation of a VerifiedPortPath
func (v VerifiedPortPath) ToString() string {
	if v.portName == nil {
		return v.streamletRef
	}
	return fmt.Sprintf("%s.%s", v.streamletRef, *v.portName)
}

// NewVerifiedPortPath constructs a VerifiedPortPath out of a string
func NewVerifiedPortPath(portPath string) (*VerifiedPortPath, *InvalidPortPath) {
	var trimmed = strings.TrimSpace(portPath)
	var splitF = func(c rune) bool {
		return c == '.'
	}
	var parts = strings.FieldsFunc(trimmed, splitF)

	if strings.HasPrefix(trimmed, ".") {
		return nil, &InvalidPortPath{
			PortPathError: PortPathError{},
			path:          portPath}
	} else if len(parts) == 1 && !strings.HasSuffix(portPath, ".") {
		return &VerifiedPortPath{parts[0], nil}, nil
	} else if len(parts) >= 2 {
		var portName = parts[len(parts)-1]
		var streamletNamePart = parts[:len(parts)-1]
		var streamletRef = mkString(streamletNamePart, ".")
		if len(streamletRef) == 0. {
			return nil, &InvalidPortPath{
				PortPathError: PortPathError{},
				path:          portPath}
		} else if len(portName) == 0 {
			return nil, &InvalidPortPath{
				PortPathError: PortPathError{},
				path:          portPath}
		} else {
			return &VerifiedPortPath{parts[0], &parts[1]}, nil
		}
	} else {
		return nil, &InvalidPortPath{
			PortPathError: PortPathError{},
			path:          portPath}
	}
}

func (v *VerifiedInlet) portPath() VerifiedPortPath {
	return VerifiedPortPath{
		v.streamlet.name,
		&v.VerifiedPort.portName,
	}
}

func (v *VerifiedOutlet) portPath() VerifiedPortPath {
	return VerifiedPortPath{
		v.streamlet.name,
		&v.VerifiedPort.portName,
	}
}

func (v *VerifiedOutlet) matches(outletDescriptor OutletDescriptor) bool {
	return outletDescriptor.Name == v.portName && outletDescriptor.Schema.Fingerprint == v.schemaDescriptor.Fingerprint
}

// FindVerifiedOutlet finds the VerifiedOutlet corresponding to the outletPortPath from the list of
// verifiedStreamlets
func FindVerifiedOutlet(verifiedStreamlets []VerifiedStreamlet, outletPortPath string) (*VerifiedOutlet, BlueprintProblem) {
	verifiedPortPath, err := NewVerifiedPortPath(outletPortPath)
	fmt.Printf("verified port path %s\n", *&verifiedPortPath.streamletRef)

	if err != nil {
		return nil, err
	}

	var found *VerifiedStreamlet
	for _, verifiedStreamlet := range verifiedStreamlets {
		if verifiedStreamlet.name == verifiedPortPath.streamletRef {
			found = &verifiedStreamlet
			break
		}
	}
	if found == nil {
		return nil, PortPathNotFound{
			path: outletPortPath,
		}
	}
	if len(*verifiedPortPath.portName) == 0 && len(found.descriptor.Outlets) > 1 {
		var outletsToMap = found.descriptor.Outlets
		var suggestions []VerifiedPortPath

		for _, outletToMap := range outletsToMap {
			finalNameStr := outletToMap.Name
			suggestions = append(suggestions, VerifiedPortPath{
				streamletRef: verifiedPortPath.streamletRef,
				portName:     &finalNameStr})
		}
		return nil, PortPathNotFound{
			path:        outletPortPath,
			suggestions: suggestions,
		}
	}
	var portPath *VerifiedPortPath
	if len(*verifiedPortPath.portName) == 0 && len(found.descriptor.Outlets) == 1 {
		finalNameStr := found.descriptor.Outlets[0].Name
		portPath = &VerifiedPortPath{
			streamletRef: verifiedPortPath.streamletRef,
			portName:     &finalNameStr}

	} else {
		portPath = verifiedPortPath
	}

	var foundOutlet *VerifiedOutlet
	for _, outlet := range found.descriptor.Outlets {
		if outlet.Name == *portPath.portName {
			foundOutlet = &VerifiedOutlet{
				streamlet: *found,
				VerifiedPort: VerifiedPort{
					portName:         outlet.Name,
					schemaDescriptor: outlet.Schema,
				},
			}
			break
		}
	}

	if foundOutlet != nil {
		return foundOutlet, nil
	}
	return nil, PortPathNotFound{
		path: outletPortPath,
	}
}

// FindVerifiedInlet finds the VerifiedOutlet corresponding to the inletPortPath from the list of
// verifiedStreamlets
func FindVerifiedInlet(verifiedStreamlets []VerifiedStreamlet, inletPortPath string) (*VerifiedInlet, BlueprintProblem) {
	verifiedPortPath, err := NewVerifiedPortPath(inletPortPath)

	if err != nil {
		return nil, err
	}
	var found *VerifiedStreamlet
	for _, verifiedStreamlet := range verifiedStreamlets {
		if verifiedStreamlet.name == verifiedPortPath.streamletRef {
			found = &verifiedStreamlet
			break
		}
	}
	if found == nil {
		return nil, PortPathNotFound{
			path: inletPortPath,
		}
	}
	if len(*verifiedPortPath.portName) == 0 && len(found.descriptor.Inlets) > 1 {
		var inletsToMap = found.descriptor.Inlets
		var suggestions []VerifiedPortPath

		for _, inletToMap := range inletsToMap {
			finalNameStr := inletToMap.Name
			suggestions = append(suggestions, VerifiedPortPath{
				streamletRef: verifiedPortPath.streamletRef,
				portName:     &finalNameStr})
		}
		return nil, PortPathNotFound{
			path:        inletPortPath,
			suggestions: suggestions,
		}
	}
	var portPath *VerifiedPortPath
	if len(*verifiedPortPath.portName) == 0 && len(found.descriptor.Inlets) == 1 {
		finalNameStr := found.descriptor.Inlets[0].Name
		portPath = &VerifiedPortPath{
			streamletRef: verifiedPortPath.streamletRef,
			portName:     &finalNameStr}

	} else {
		portPath = verifiedPortPath
	}

	var foundInlet *VerifiedInlet
	for _, inlet := range found.descriptor.Inlets {
		if inlet.Name == *portPath.portName {
			foundInlet = &VerifiedInlet{
				streamlet: *found,
				VerifiedPort: VerifiedPort{
					portName:         inlet.Name,
					schemaDescriptor: inlet.Schema,
				},
			}
			break
		}
	}

	if foundInlet != nil {
		return foundInlet, nil
	}
	return nil, PortPathNotFound{
		path: inletPortPath,
	}
}
