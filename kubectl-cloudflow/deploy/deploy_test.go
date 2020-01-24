package deploy

import (
	"encoding/json"
	"fmt"
	"os"
	"testing"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/rayroestenburg/configuration"
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

	configs := make(map[string]*configuration.Config)
	err := ValidateConfigurationAgainstDescriptor(spec, configs)
	assert.NotEmpty(t, err)

	args := SplitConfigurationParameters(commandLineForConfiguration())
	configs = addArguments(spec, configs, args)

	err = ValidateConfigurationAgainstDescriptor(spec, configs)
	assert.Empty(t, err)

	half := SplitConfigurationParameters([]string{`valid-logger.log-level="warning"`})
	configs = make(map[string]*configuration.Config)
	configs = addArguments(spec, configs, half)
	err = ValidateConfigurationAgainstDescriptor(spec, configs)
	assert.NotEmpty(t, err)
}

func Test_CreateSecretsData(t *testing.T) {
	applicationConfiguration := domain.TestApplicationDescriptor()

	var spec domain.CloudflowApplicationSpec
	json.Unmarshal([]byte(applicationConfiguration), &spec)

	args := SplitConfigurationParameters(commandLineForConfiguration())

	// TODO add test for configs
	configs := make(map[string]*configuration.Config)
	configs = addArguments(spec, configs, args)
	fmt.Printf("Configs: \n%s\n", configs)
	secrets, err := createSecretsData(&spec, configs)
	assert.Empty(t, err)

	assert.NotEmpty(t, secrets)
	fmt.Printf("Secrets: \n%s\n", secrets)
	config := configuration.ParseString(secrets["valid-logger"].StringData["secret.conf"])
	assert.True(t, config.GetString("cloudflow.streamlets.valid-logger.log-level") == "warning")
	assert.True(t, config.GetString("cloudflow.streamlets.valid-logger.msg-prefix") == "test")
}

func Test_loadAndMergeConfigs(t *testing.T) {
	conf, err := loadAndMergeConfigs([]string{"non-existing.conf", "non-existing.conf"})
	assert.NotEmpty(t, err)

	_, err = loadAndMergeConfigs([]string{"non-existing.conf", "test_config_files/test1.conf"})
	assert.NotEmpty(t, err)

	conf, err = loadAndMergeConfigs([]string{"test_config_files/test1.conf", "test_config_files/test2.conf"})
	assert.Empty(t, err)
	assert.Equal(t, "5m", conf.GetString("cloudflow.streamlets.cdr-aggregator.watermark"))
	assert.Equal(t, "12m", conf.GetString("cloudflow.streamlets.cdr-aggregator.group-by-window"))
	assert.EqualValues(t, 5, conf.GetInt32("cloudflow.streamlets.cdr-generator1.records-per-second"))

	conf, err = loadAndMergeConfigs([]string{"test_config_files/test1.conf", "test_config_files/test2.conf", "test_config_files/test3.conf"})
	assert.Empty(t, err)
	assert.Equal(t, "5m", conf.GetString("cloudflow.streamlets.cdr-aggregator.watermark"))
	assert.Equal(t, "11m", conf.GetString("cloudflow.streamlets.cdr-aggregator.group-by-window"))
	assert.EqualValues(t, 5, conf.GetInt32("cloudflow.streamlets.cdr-generator1.records-per-second"))
	assert.Equal(t, "WARNING", conf.GetString("cloudflow.streamlets.cdr-aggregator.application-conf.akka.loglevel"))
}

func Test_addDefaultValues(t *testing.T) {

	conf, err := loadAndMergeConfigs([]string{"test_config_files/test1.conf", "test_config_files/test2.conf", "test_config_files/test3.conf"})
	assert.Empty(t, err)
	spec := domain.CloudflowApplicationSpec{
		Streamlets: []domain.Streamlet{
			domain.Streamlet{
				Descriptor: domain.Descriptor{
					ConfigParameters: []domain.ConfigParameterDescriptor{
						domain.ConfigParameterDescriptor{
							Key:          "group-by-window",
							DefaultValue: "10m",
						},
						domain.ConfigParameterDescriptor{
							Key:          "watermark",
							DefaultValue: "10m",
						},
					},
				},
				Name: "cdr-aggregator",
			},
		},
	}
	conf = addDefaultValues(spec, conf)
	assert.Equal(t, "5m", conf.GetString("cloudflow.streamlets.cdr-aggregator.watermark"))
	assert.Equal(t, "11m", conf.GetString("cloudflow.streamlets.cdr-aggregator.group-by-window"))

	conf, err = loadAndMergeConfigs([]string{"test_config_files/test1.conf"})
	assert.Empty(t, err)
	conf = addDefaultValues(spec, conf)
	assert.Equal(t, "10m", conf.GetString("cloudflow.streamlets.cdr-aggregator.watermark"))
	assert.Equal(t, "12m", conf.GetString("cloudflow.streamlets.cdr-aggregator.group-by-window"))
}
