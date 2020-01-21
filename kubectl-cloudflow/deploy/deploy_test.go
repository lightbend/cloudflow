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

func Test_validateConfigurationAgainstDescriptor(t *testing.T) {

	applicationConfiguration := domain.TestApplicationDescriptor()

	var spec domain.CloudflowApplicationSpec
	json.Unmarshal([]byte(applicationConfiguration), &spec)

	configs := make(map[string]*configuration.Config)
	err := validateConfigurationAgainstDescriptor(spec, configs)
	assert.NotEmpty(t, err)

	args := SplitConfigurationParameters(commandLineForConfiguration())
	configs = addArguments(spec, configs, args)

	err = validateConfigurationAgainstDescriptor(spec, configs)
	assert.Empty(t, err)

	half := SplitConfigurationParameters([]string{`valid-logger.log-level="warning"`})
	configs = make(map[string]*configuration.Config)
	configs = addArguments(spec, configs, half)
	err = validateConfigurationAgainstDescriptor(spec, configs)
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

	conf, err = loadAndMergeConfigs([]string{"test_config_files/cdr-aggregator.conf", "test_config_files/test1.conf"})
	assert.Empty(t, err)
	assert.Equal(t, "2m", conf.GetString("cloudflow.streamlets.cdr-aggregator.watermark"))
	assert.Equal(t, "12m", conf.GetString("cloudflow.streamlets.cdr-aggregator.group-by-window"))
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

func Test_addArguments(t *testing.T) {

	aggConf := configuration.LoadConfig("test_config_files/cdr-aggregator.conf")

	genConf := configuration.LoadConfig("test_config_files/cdr-generator1.conf")
	conf := EmptyConfig()

	spec := domain.CloudflowApplicationSpec{
		Streamlets: []domain.Streamlet{
			domain.Streamlet{
				Descriptor: domain.Descriptor{
					ConfigParameters: []domain.ConfigParameterDescriptor{
						domain.ConfigParameterDescriptor{
							Key:          "group-by-window",
							DefaultValue: "10m",
							Type:         "duration",
						},
						domain.ConfigParameterDescriptor{
							Key:          "watermark",
							DefaultValue: "10m",
							Type:         "duration",
						},
					},
				},
				Name: "cdr-aggregator",
			},
			domain.Streamlet{
				Descriptor: domain.Descriptor{
					ConfigParameters: []domain.ConfigParameterDescriptor{
						domain.ConfigParameterDescriptor{
							Key:          "records-per-second",
							DefaultValue: "10",
							Type:         "int32",
						},
					},
				},
				Name: "cdr-generator1",
			},
		},
	}

	configs := addExistingStreamletConfigsIfNotPresentInFile(spec, conf, map[string]*configuration.Config{
		"cdr-aggregator": aggConf,
		"cdr-generator1": genConf,
	})

	configsAdded := addArguments(spec, configs, map[string]string{
		"cdr-aggregator.group-by-window":    "14m",
		"cdr-generator1.records-per-second": "100",
	})

	assert.EqualValues(t, 100, configsAdded["cdr-generator1"].GetInt32("cloudflow.streamlets.cdr-generator1.records-per-second"))
	assert.Equal(t, "14m", configsAdded["cdr-aggregator"].GetString("cloudflow.streamlets.cdr-aggregator.group-by-window"))
	assert.Equal(t, "2m", configsAdded["cdr-aggregator"].GetString("cloudflow.streamlets.cdr-aggregator.watermark"))

	configs = addExistingStreamletConfigsIfNotPresentInFile(spec, conf, map[string]*configuration.Config{
		"cdr-aggregator": aggConf,
		"cdr-generator1": genConf,
	})

	configsAdded = addArguments(spec, configs, map[string]string{
		"cdr-aggregator.group-by-window": "2m",
	})
	assert.Equal(t, "2m", configsAdded["cdr-aggregator"].GetString("cloudflow.streamlets.cdr-aggregator.group-by-window"))
	assert.EqualValues(t, 8, configsAdded["cdr-generator1"].GetInt32("cloudflow.streamlets.cdr-generator1.records-per-second"))
}

func Test_addExistingStreamletConfigsIfNotPresentInFile(t *testing.T) {
	aggConf := configuration.LoadConfig("test_config_files/cdr-aggregator.conf")

	genConf := configuration.LoadConfig("test_config_files/cdr-generator1.conf")

	conf, err := loadAndMergeConfigs([]string{"test_config_files/test1.conf", "test_config_files/test2.conf", "test_config_files/test3.conf"})
	assert.Empty(t, err)

	spec := domain.CloudflowApplicationSpec{
		Streamlets: []domain.Streamlet{
			domain.Streamlet{
				Name: "cdr-aggregator",
			},
			domain.Streamlet{
				Name: "cdr-generator1",
			},
		},
	}

	// conf (merged files) takes precedence
	configs := addExistingStreamletConfigsIfNotPresentInFile(spec, conf, map[string]*configuration.Config{
		"cdr-aggregator": aggConf,
		"cdr-generator1": genConf,
	})
	// test2.conf
	assert.EqualValues(t, 5, configs["cdr-generator1"].GetInt32("cloudflow.streamlets.cdr-generator1.records-per-second"))
	// test3.conf
	assert.Equal(t, "11m", configs["cdr-aggregator"].GetString("cloudflow.streamlets.cdr-aggregator.group-by-window"))
	// test2.conf
	assert.Equal(t, "5m", configs["cdr-aggregator"].GetString("cloudflow.streamlets.cdr-aggregator.watermark"))
}

func Test_validateConfigFiles(t *testing.T) {
	aggConf := configuration.LoadConfig("test_config_files/cdr-aggregator.conf")

	genConf := configuration.LoadConfig("test_config_files/cdr-generator1.conf")
	conf := EmptyConfig()

	spec := domain.CloudflowApplicationSpec{
		Streamlets: []domain.Streamlet{
			domain.Streamlet{
				Descriptor: domain.Descriptor{
					ConfigParameters: []domain.ConfigParameterDescriptor{
						domain.ConfigParameterDescriptor{
							Key:          "group-by-window",
							DefaultValue: "10m",
							Type:         "duration",
						},
						domain.ConfigParameterDescriptor{
							Key:          "watermark",
							DefaultValue: "10m",
							Type:         "duration",
						},
					},
				},
				Name: "cdr-aggregator",
			},
			domain.Streamlet{
				Descriptor: domain.Descriptor{
					ConfigParameters: []domain.ConfigParameterDescriptor{
						domain.ConfigParameterDescriptor{
							Key:          "records-per-second",
							DefaultValue: "10",
							Type:         "int32",
						},
					},
				},
				Name: "cdr-generator1",
			},
		},
	}

	configs := addExistingStreamletConfigsIfNotPresentInFile(spec, conf, map[string]*configuration.Config{
		"cdr-aggregator": aggConf,
		"cdr-generator1": genConf,
	})

	err := validateConfigurationAgainstDescriptor(spec, configs)
	assert.Empty(t, err)

	aggConf = configuration.LoadConfig("test_config_files/bad-cdr-aggregator.conf")
	configs = addExistingStreamletConfigsIfNotPresentInFile(spec, conf, map[string]*configuration.Config{
		"cdr-aggregator": aggConf,
		"cdr-generator1": genConf,
	})
	err = validateConfigurationAgainstDescriptor(spec, configs)
	assert.NotEmpty(t, err)
}

func Test_addApplicationLevelConfig(t *testing.T) {

	aggConf := configuration.LoadConfig("test_config_files/cdr-aggregator.conf")

	conf, err := loadAndMergeConfigs([]string{"test_config_files/cdr-aggregator-with-app-level.conf"})
	spec := domain.CloudflowApplicationSpec{
		Streamlets: []domain.Streamlet{
			domain.Streamlet{
				Name: "cdr-aggregator",
			},
		},
	}

	configs := addExistingStreamletConfigsIfNotPresentInFile(spec, conf, map[string]*configuration.Config{
		"cdr-aggregator": aggConf,
	})

	assert.Empty(t, err)
	added := addApplicationLevelConfig(conf, configs)
	// the existing config is not used, since there is a config for the cdr-aggregator in the merged files.
	assert.Equal(t, "", added["cdr-aggregator"].GetString("cloudflow.streamlets.cdr-aggregator.watermark"))
	assert.Equal(t, "INFO", added["cdr-aggregator"].GetString("akka.loglevel"))
	assert.Equal(t, "my-service", added["cdr-aggregator"].GetString("akka.kafka.producer.service-name"))
}

func Test_addApplicationLevelConfig2(t *testing.T) {

	conf, err := loadAndMergeConfigs([]string{"test_config_files/cdr-aggregator.conf", "test_config_files/cdr-aggregator-with-app-level.conf"})
	spec := domain.CloudflowApplicationSpec{
		Streamlets: []domain.Streamlet{
			domain.Streamlet{
				Name: "cdr-aggregator",
			},
		},
	}
	configs := addExistingStreamletConfigsIfNotPresentInFile(spec, conf, make(map[string]*configuration.Config))

	assert.Empty(t, err)
	added := addApplicationLevelConfig(conf, configs)
	assert.Equal(t, "2m", added["cdr-aggregator"].GetString("cloudflow.streamlets.cdr-aggregator.watermark"))
	assert.Equal(t, "INFO", added["cdr-aggregator"].GetString("akka.loglevel"))
	assert.Equal(t, "my-service", added["cdr-aggregator"].GetString("akka.kafka.producer.service-name"))
}

//Tests workaround for merging.
func Test_mergeWithFallback(t *testing.T) {
	os.Setenv("FOO", "1")
	left := configuration.ParseString(`
     a.b.c {
			 d = 2
			 e = 3
			 g {
				 h = 8
			 }
		 }
		 z = 7
		 n {
			 o.p {
				 q.r = 45
				 s = ${FOO}
				 t = 15
				 t = ${?FOO}
				 u = ${?BAR}
				 v = ["1", "2", "3"]
			 }
		 }

		 # note, after mixing this format , nothing else is read. (this might be according to spec)
		 {
			 x : 12
		 }
	`)
	right := configuration.ParseString(`
     a.b.c {
			 d = 2
			 e = 4
			 f = 10
			 g {
				 h = 8
			 }
		 }
		 z = 12
		 # NOTE: This is not supported yet.
		 # p = ${a.b.c}
		 # p = ${a.b.c.d}

		 i : {
			 j : {
				 k.l.m = 20
			 }
		 }

		 n {
			 o.p {
				 q.r = 44
				 v = ["4", "5", "6"]
			 }
		 }

		 # note, after mixing this format , nothing else is read. (this might be according to spec)
		 {
			 x : 13
		 }

	`)

	res := mergeWithFallback(left, right)
	assert.Equal(t, "3", res.GetString("a.b.c.e"))
	assert.Equal(t, "10", res.GetString("a.b.c.f"))
	assert.Equal(t, "8", res.GetString("a.b.c.g.h"))
	assert.Equal(t, "7", res.GetString("z"))
	assert.Equal(t, "12", res.GetString("x"))
	assert.Equal(t, "20", res.GetString("i.j.k.l.m"))
	assert.Equal(t, "45", res.GetString("n.o.p.q.r"))
	assert.Equal(t, "1", res.GetString("n.o.p.s"))
	assert.Equal(t, "1", res.GetString("n.o.p.t"))
	assert.Equal(t, "", res.GetString("n.o.p.u"))
	assert.EqualValues(t, []string{"1", "2", "3"}, res.GetStringList("n.o.p.v"))
	res = mergeWithFallback(right, left)
	assert.Equal(t, "4", res.GetString("a.b.c.e"))
	assert.Equal(t, "8", res.GetString("a.b.c.g.h"))
	assert.Equal(t, "12", res.GetString("z"))
	assert.Equal(t, "13", res.GetString("x"))
	assert.Equal(t, "20", res.GetString("i.j.k.l.m"))
	assert.Equal(t, "44", res.GetString("n.o.p.q.r"))
	assert.EqualValues(t, []string{"4", "5", "6"}, res.GetStringList("n.o.p.v"))

	// NOTE: Uncomment this ONLY to see where go-akka/configuration fails merging
	// res = left.WithFallback(right)
	// assert.Equal(t, "3", res.GetString("a.b.c.e"))
	// assert.Equal(t, "10", res.GetString("a.b.c.f"))
	// res = right.WithFallback(left)
	// assert.Equal(t, "4", res.GetString("a.b.c.e"))
}
