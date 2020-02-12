package verify

import (
	"crypto/sha256"
	"fmt"
	"regexp"
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
		if match[0] != 0 {
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

func Diff(a, b []string) (diff []string) {
	m := make(map[string]bool)

	for _, item := range b {
		m[item] = true
	}

	for _, item := range a {
		if _, ok := m[item]; !ok {
			diff = append(diff, item)
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
