package deploy

import (
	"encoding/json"
	"fmt"
	"strings"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
)


func unique(stringSlice []string) []string {
	keys := make(map[string]bool)
	list := []string{}
	for _, entry := range stringSlice {
		if _, value := keys[entry]; !value {
			keys[entry] = true
			list = append(list, entry)
		}
	}
	return list
}

var _ = Describe("Deploy", func() {

	Context("With configuration parameters", func() {
		It("should split configuration parameters", func() {
			result := SplitConfigurationParameters([]string{
				`a="b"`,
				"key=ddd",
				`some.key="some, value"`,
				`some.key2=c29tZSBzdHJpbmc9PQ==`,
				`some.key3="some text passed to a streamlet"`,
				`some.key4=post Cobra processed, unquoted string`})

			Expect(result).To(HaveLen(6))
			Expect(result["a"]).To(Equal("b"))
			Expect(result["key"]).To(Equal("ddd"))
			Expect(result["some.key"]).To(Equal("some, value"))
			Expect(result["some.key2"]).To(Equal("c29tZSBzdHJpbmc9PQ=="))
			Expect(result["some.key3"]).To(Equal("some text passed to a streamlet"))
			Expect(result["some.key4"]).To(Equal("post Cobra processed, unquoted string"))

			empty := SplitConfigurationParameters([]string{})
			Expect(len(empty)).To(Equal(0))
		})

		It("should validate configuration against descriptor", func() {
			applicationConfiguration := cloudflowapplication.TestApplicationDescriptor()

			var spec cloudflowapplication.CloudflowApplicationSpec
			json.Unmarshal([]byte(applicationConfiguration), &spec)

			var config []string
			_, err := ValidateConfigurationAgainstDescriptor(spec, SplitConfigurationParameters(config))
			Expect(err).To(HaveOccurred())

			properConfig := SplitConfigurationParameters(commandLineForConfiguration())

			_, err = ValidateConfigurationAgainstDescriptor(spec, properConfig)
			Expect(err).NotTo(HaveOccurred())

			half := SplitConfigurationParameters([]string{`valid-logger.log-level="warning"`})
			_, err = ValidateConfigurationAgainstDescriptor(spec, half)
			Expect(err).To(HaveOccurred())
		})
	})

	Context("With secrets data", func() {
		It("should create secrets data", func() {
			applicationConfiguration := cloudflowapplication.TestApplicationDescriptor()

			var spec cloudflowapplication.CloudflowApplicationSpec
			json.Unmarshal([]byte(applicationConfiguration), &spec)

			properConfig := SplitConfigurationParameters(commandLineForConfiguration())

			secrets := CreateSecretsData(&spec, properConfig)

			Expect(len(secrets)).To(BeNumerically(">=", 0))
			Expect(len(secrets["valid-logger"].Name)).To(BeNumerically("<=", 63))
			Expect(len(secrets["valid-logger"].StringData["secret-conf"])).To(BeNumerically(">=", 0))

			configValues, _ := parseCommandLine(secrets["valid-logger"].StringData["secret.conf"])
			var values []string
			var keys []string
			for i := range configValues {
				configValue := strings.Trim(configValues[i], " \n\r\t")
				splitValues := strings.Split(configValue, "=")
				Expect(splitValues).To(HaveLen(2))
				keys = append(keys, strings.Trim(splitValues[0], " \t\n\r"))
				values = append(values, strings.Trim(splitValues[1], " \t\n\r"))
			}
			Expect(keys[0]).To(Equal("cloudflow.streamlets.valid-logger.log-level"))
			Expect(values[0]).To(Equal("warning"))

			Expect(keys[1]).To(Equal("cloudflow.streamlets.valid-logger.msg-prefix"))
			Expect(values[1]).To(Equal("test"))
		})
	})
})

func commandLineForConfiguration() []string {
	return []string{
		`valid-logger.log-level=warning`,
		`valid-logger.msg-prefix=test`,
		`valid-logger.msg-prefix-2="test"`,
		`valid-logger.msg-prefix-3=20`,
		`valid-logger.msg-prefix-4=3.15`,
		`valid-logger.msg-prefix-5=true`,
		`valid-logger.msg-prefix-6="20 minutes"`,
		`valid-logger.msg-prefix-7="20M"`}
}

func parseCommandLine(commandLine string) ([]string, error) {
	var args []string
	state := "start"
	current := ""
	quote := "\""
	escapeNext := true
	command := strings.Trim(commandLine, " \t\r\n")
	for i := 0; i < len(command); i++ {
		c := command[i]

		if state == "quotes" {
			if string(c) != quote {
				current += string(c)
			} else {
				args = append(args, current)
				current = ""
				state = "start"
			}
			continue
		}

		if escapeNext {
			current += string(c)
			escapeNext = false
			continue
		}

		if c == '\\' {
			escapeNext = true
			continue
		}

		if c == '"' || c == '\'' {
			state = "quotes"
			quote = string(c)
			continue
		}

		if state == "arg" {
			if c == ' ' || c == '\t' {
				args = append(args, current)
				current = ""
				state = "start"
			} else {
				current += string(c)
			}
			continue
		}

		if c != ' ' && c != '\t' {
			state = "arg"
			current += string(c)
		}
	}

	if state == "quotes" {
		return []string{}, fmt.Errorf("unclosed quote in command line: %s", command)
	}

	if current != "" {
		args = append(args, current)
	}

	return args, nil
}
