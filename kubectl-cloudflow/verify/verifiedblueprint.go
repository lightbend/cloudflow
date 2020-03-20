package verify

import (
	"fmt"
	"strings"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
)

// SchemaDescriptor is a domain expressive typedef
type SchemaDescriptor cloudflowapplication.InOutletSchema

// OutletDescriptor is a domain expressive typedef
type OutletDescriptor cloudflowapplication.InOutlet

// InletDescriptor is a domain expressive typedef
type InletDescriptor cloudflowapplication.InOutlet

type VerifiedStreamlet struct {
	name       string
	descriptor StreamletDescriptor
}

type VerifiedPortPath struct {
	streamletRef string
	portName     *string
}

type VerifiedPort struct {
	portName         string
	schemaDescriptor cloudflowapplication.InOutletSchema
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

	if err != nil {
		return nil, err
	}

	var foundVerifiedStreamlet *VerifiedStreamlet
	for _, verifiedStreamlet := range verifiedStreamlets {
		if verifiedStreamlet.name == verifiedPortPath.streamletRef {
			foundVerifiedStreamlet = &verifiedStreamlet
			break
		}
	}
	if foundVerifiedStreamlet == nil {
		return nil, PortPathNotFound{
			path: outletPortPath,
		}
	}
	if verifiedPortPath.portName == nil && len(foundVerifiedStreamlet.descriptor.Outlets) > 1 {
		var outletsToMap = foundVerifiedStreamlet.descriptor.Outlets
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
	if verifiedPortPath.portName == nil && len(foundVerifiedStreamlet.descriptor.Outlets) == 1 {
		finalNameStr := foundVerifiedStreamlet.descriptor.Outlets[0].Name
		portPath = &VerifiedPortPath{
			streamletRef: verifiedPortPath.streamletRef,
			portName:     &finalNameStr}

	} else {
		portPath = verifiedPortPath
	}

	var foundOutlet *VerifiedOutlet
	for _, outlet := range foundVerifiedStreamlet.descriptor.Outlets {
		if portPath.portName != nil && outlet.Name == *portPath.portName {
			foundOutlet = &VerifiedOutlet{
				streamlet: *foundVerifiedStreamlet,
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

// FindVerifiedInlet finds the VerifiedInlet corresponding to the inletPortPath from the list of
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
	if verifiedPortPath.portName == nil && len(found.descriptor.Inlets) > 1 {
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
	if verifiedPortPath.portName == nil && len(found.descriptor.Inlets) == 1 {
		finalNameStr := found.descriptor.Inlets[0].Name
		portPath = &VerifiedPortPath{
			streamletRef: verifiedPortPath.streamletRef,
			portName:     &finalNameStr}

	} else {
		portPath = verifiedPortPath
	}

	var foundInlet *VerifiedInlet
	for _, inlet := range found.descriptor.Inlets {
		if portPath.portName != nil && inlet.Name == *portPath.portName {
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
