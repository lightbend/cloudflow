package deploy

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/stretchr/testify/assert"
)

func TestMain(m *testing.M) {
	code := m.Run()
	os.Exit(code)
}

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

func Test_SplitConfigurationParameters(t *testing.T) {
	result := SplitConfigurationParameters([]string{
		`a="b"`,
		"key=ddd",
		`some.key="some, value"`,
		`some.key2=c29tZSBzdHJpbmc9PQ==`,
		`some.key3="some text passed to a streamlet"`,
		`some.key4=post Cobra processed, unquoted string`})

	assert.NotEmpty(t, result)
	assert.Equal(t, result["a"], "b")
	assert.Equal(t, result["key"], "ddd")
	assert.Equal(t, result["some.key"], "some, value")
	assert.Equal(t, result["some.key2"], "c29tZSBzdHJpbmc9PQ==")
	assert.Equal(t, result["some.key3"], "some text passed to a streamlet")
	assert.Equal(t, result["some.key4"], "post Cobra processed, unquoted string")

	empty := SplitConfigurationParameters([]string{})
	assert.Empty(t, empty)
}

func Test_ValidateConfigurationAgainstDescriptor(t *testing.T) {

	applicationConfiguration := domain.TestApplicationDescriptor()

	var spec domain.CloudflowApplicationSpec
	json.Unmarshal([]byte(applicationConfiguration), &spec)

	var config []string
	_, err := ValidateConfigurationAgainstDescriptor(spec, SplitConfigurationParameters(config))
	assert.NotEmpty(t, err)

	properConfig := SplitConfigurationParameters(commandLineForConfiguration())

	_, err = ValidateConfigurationAgainstDescriptor(spec, properConfig)
	assert.Empty(t, err)

	half := SplitConfigurationParameters([]string{`valid-logger.log-level="warning"`})
	_, err = ValidateConfigurationAgainstDescriptor(spec, half)
	assert.NotEmpty(t, err)
}

func Test_CreateSecretsData(t *testing.T) {
	applicationConfiguration := domain.TestApplicationDescriptor()

	var spec domain.CloudflowApplicationSpec
	json.Unmarshal([]byte(applicationConfiguration), &spec)

	properConfig := SplitConfigurationParameters(commandLineForConfiguration())

	secrets := CreateSecretsData(&spec, properConfig)

	assert.NotEmpty(t, secrets)
	assert.True(t, len(secrets["valid-logger"].Name) <= 63)
	assert.NotEmpty(t, secrets["valid-logger"].StringData["secret.conf"])
	configValues, _ := parseCommandLine(secrets["valid-logger"].StringData["secret.conf"])
	var values []string
	var keys []string
	for i := range configValues {
		configValue := strings.Trim(configValues[i], " \n\r\t")
		splitValues := strings.Split(configValue, "=")
		assert.True(t, len(splitValues) == 2)
		keys = append(keys, strings.Trim(splitValues[0], " \t\n\r"))
		values = append(values, strings.Trim(splitValues[1], " \t\n\r"))
	}
	assert.True(t, keys[0] == "cloudflow.streamlets.valid-logger.log-level")
	assert.True(t, values[0] == "warning")

	assert.True(t, keys[1] == "cloudflow.streamlets.valid-logger.msg-prefix")
	assert.True(t, values[1] == "test")
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
