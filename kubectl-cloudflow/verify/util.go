package verify

import (
	"crypto/sha256"
	"fmt"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"regexp"
	"strings"
)

var DNSLabelPattern = regexp.MustCompile(`^[a-z0-9]([-a-z0-9]*[a-z0-9])?$`)
var ConfigParameterKeyPattern = regexp.MustCompile(`[a-zA-Z]+(-[a-zA-Z-0-9]+)*`)

const DNS1123LabelMaxLength = 63

// This pattern follows the java identifier rules to parse the class name.
var ClassNamePattern = regexp.MustCompile(`([\p{L}_$][\p{L}\p{N}_$]*\.)*[\p{L}_$][\p{L}\p{N}_$]*`)

func CheckFullPatternMatch(input string, regexp* regexp.Regexp) bool {
	var match = regexp.FindStringIndex(input)
	if match == nil {
		return false
	} else {
		if match[0] != 0 || (match[0] + match[1]) != len(input) {
			return false
		} else {
			return true
		}
	}
}

func IsDnsLabelCompatible(name string) bool {
	return DNSLabelPattern.MatchString(name)
}

func mkString(toFormat []string, separator string) string {
	var res = ""
	for _, str := range toFormat {
		if res == "" {
			res = str
		} else {
			res = res + separator + str
		}
	}
	return res
}

// Creates a hash value of a struct, to be used with a go map
// Alternatives are https://github.com/mitchellh/hashstructure
// and https://github.com/cnf/structhash, but they return an error.
func GetSHA256Hash (o interface{}) string {
	h := sha256.New()
	h.Write([]byte(fmt.Sprintf("%v", o)))
	return fmt.Sprintf("%x", h.Sum(nil))
}

// A Scala like diff method
func Diff(a, b []string) (diff []string) {
	aMap := make(map[string]int)
	bMap := make(map[string]int)

	for _, item := range a {
		aMap[item] = aMap[item] + 1
	}

	for _, item := range b {
		bMap[item] = bMap[item] + 1
	}

	for _, item := range Distinct(a) {
		if _, ok := bMap[item]; !ok {
			diff = append(diff, item)
		} else {
			aValue := aMap[item]
			bValue := bMap[item]
			for  i :=1; i <= (aValue - bValue); i++ {
				diff = append(diff, item)
			}
		}
	}
	return
}

func Distinct(input []string) []string {
	keys := make(map[string]bool)
	var dist []string
	for _, entry := range input {
		if _, value := keys[entry]; !value {
			keys[entry] = true
			dist = append(dist, entry)
		}
	}
	return dist
}

func ParseImageReference(imageURI string) (*domain.ImageReference, error) {

	imageRef := strings.TrimSpace(imageURI)
	msg := "The following docker image path is not valid:\n\n%s\n\nA common error is to prefix the image path with a URI scheme like 'http' or 'https'."

	if strings.HasPrefix(imageRef, ":") ||
		strings.HasSuffix(imageRef, ":") ||
		strings.HasPrefix(imageRef, "http://") ||
		strings.HasPrefix(imageRef, "https://") {
		return nil, fmt.Errorf(msg, imageRef)
	}

	/*
	 See https://docs.docker.com/engine/reference/commandline/tag/
	 A tag name must be valid ASCII and may contain lowercase and uppercase letters, digits, underscores, periods and dashes.
	 A tag name may not start with a period or a dash and may contain a maximum of 128 characters.
	 A tag contain lowercase and uppercase letters, digits, underscores, periods and dashes
	 (It can also contain a : which the docs don't mention, for instance sha256:<hash>)
	*/
	imageRefRegex := regexp.MustCompile(`^((?P<reg>([a-zA-Z0-9-.:]{0,253}))/)?(?P<repo>(?:[a-z0-9-_./]+/)?)(?P<image>[a-z0-9-_.]+)(?:[:@](?P<tag>[^.-][a-zA-Z0-9-_.:]{0,127})?)?$`)
	match := imageRefRegex.FindStringSubmatch(imageRef)

	if match == nil {
		return nil, fmt.Errorf(msg, imageRef)
	}

	result := make(map[string]string)
	for i, name := range imageRefRegex.SubexpNames() {
		if i != 0 && name != "" && i < len(match) {
			result[name] = match[i]
		}
	}
	result["uri"] = imageURI

	ir := domain.ImageReference{
		Registry:   result["reg"],
		Repository: strings.TrimSuffix(result["repo"], "/"),
		Image:      result["image"],
		Tag:        result["tag"],
		FullURI:    result["uri"],
	}

	if ir.Image == "" {
		return nil, fmt.Errorf(msg, imageRef)
	}

	if strings.HasPrefix(ir.Image, ":") || strings.HasSuffix(ir.Image, ":") {
		return nil, fmt.Errorf(msg, imageRef)
	}

	if strings.HasPrefix(ir.Tag, ".") || strings.HasPrefix(ir.Tag, "-") || strings.HasPrefix(ir.Tag, ":") || strings.HasSuffix(ir.Tag, ":") {
		return nil, fmt.Errorf(msg, imageRef)
	}

	if strings.Count(ir.Tag, ":") > 1 {
		return nil, fmt.Errorf(msg, imageRef)
	}

	// this is a shortcoming in using a regex for this, since it will always eagerly match the first part as the registry.
	if ir.Registry != "" && ir.Repository == "" {
		ir.Repository = ir.Registry
		ir.Registry = ""
	}

	return &ir, nil
}

func sliding(input []StreamletDescriptor) [][]StreamletDescriptor {
	var ret [][]StreamletDescriptor
	for i := 0; i < len(input) - 1; i++ {
		ret = append(ret,[]StreamletDescriptor{input[i], input[i + 1]})
	}
	return ret
}
