package config

import (
	"encoding/json"
	"fmt"
	"os"
	"testing"

	"github.com/go-akka/configuration"
	"github.com/lightbend/cloudflow/kubectl-cloudflow/cfapp"
	"github.com/stretchr/testify/assert"
)

func TestMain(m *testing.M) {
	code := m.Run()
	os.Exit(code)
}

func commandLineForConfiguration() []string {
	return []string{
		`cloudflow.streamlets.valid-logger.config-parameters.log-level=warning`,
		`cloudflow.streamlets.valid-logger.config-parameters.msg-prefix=test`,
		`cloudflow.streamlets.valid-logger.config-parameters.msg-prefix-2="test"`,
		`cloudflow.streamlets.valid-logger.config-parameters.msg-prefix-3=20`,
		`cloudflow.streamlets.valid-logger.config-parameters.msg-prefix-4=3.15`,
		`cloudflow.streamlets.valid-logger.config-parameters.msg-prefix-5=true`,
		`cloudflow.streamlets.valid-logger.config-parameters.msg-prefix-6="20 minutes"`,
		`cloudflow.streamlets.valid-logger.config-parameters.msg-prefix-7="20M"`}
}

func Test_SplitConfigurationParameters(t *testing.T) {
	result := splitConfigurationParameters([]string{
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

	empty := splitConfigurationParameters([]string{})
	assert.Empty(t, empty)
}

func Test_validateConfigurationAgainstDescriptor(t *testing.T) {

	applicationConfiguration := cfapp.TestApplicationDescriptor()

	var spec cfapp.CloudflowApplicationSpec
	json.Unmarshal([]byte(applicationConfiguration), &spec)

	configs := make(map[string]*configuration.Config)
	err := validateConfigurationAgainstDescriptor(spec, configs)
	assert.NotEmpty(t, err)

	args := splitConfigurationParameters(commandLineForConfiguration())
	config := EmptyConfig()
	config = addCommandLineArguments(spec, config, args)
	fmt.Println(config)

	configs = createStreamletConfigsMap(spec, config)
	fmt.Println(configs)
	err = validateConfigurationAgainstDescriptor(spec, configs)
	assert.Empty(t, err)

	half := splitConfigurationParameters([]string{`cloudflow.streamlets.valid-logger.config-parameters.log-level="warning"`})
	config = EmptyConfig()
	config = addCommandLineArguments(spec, config, half)
	configs = make(map[string]*configuration.Config)
	configs["valid-logger"] = config
	err = validateConfigurationAgainstDescriptor(spec, configs)
	assert.NotEmpty(t, err)
}

func Test_CreateSecretsData(t *testing.T) {
	applicationConfiguration := cfapp.TestApplicationDescriptor()

	var spec cfapp.CloudflowApplicationSpec
	json.Unmarshal([]byte(applicationConfiguration), &spec)

	args := splitConfigurationParameters(commandLineForConfiguration())

	config := EmptyConfig()
	config = addCommandLineArguments(spec, config, args)
	fmt.Printf("Configs: \n%s\n", config)
	configs := createStreamletConfigsMap(spec, config)
	secrets, err := createInputSecretsMap(&spec, configs)
	assert.Empty(t, err)

	assert.NotEmpty(t, secrets)
	fmt.Printf("Secrets: \n%s\n", secrets)
	config = configuration.ParseString(secrets["valid-logger"].StringData["secret.conf"])
	assert.True(t, config.GetString("cloudflow.streamlets.valid-logger.config-parameters.log-level") == "warning")
	assert.True(t, config.GetString("cloudflow.streamlets.valid-logger.config-parameters.msg-prefix") == "test")
}

func Test_loadAndMergeConfigs(t *testing.T) {
	conf, err := loadAndMergeConfigs([]string{"non-existing.conf", "non-existing.conf"})
	assert.NotEmpty(t, err)

	_, err = loadAndMergeConfigs([]string{"non-existing.conf", "test_config_files/test1.conf"})
	assert.NotEmpty(t, err)

	conf, err = loadAndMergeConfigs([]string{"test_config_files/test1.conf", "test_config_files/test2.conf"})
	assert.Empty(t, err)
	assert.Equal(t, "5m", conf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))
	assert.Equal(t, "12m", conf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
	assert.EqualValues(t, 5, conf.GetInt32("cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second"))

	conf, err = loadAndMergeConfigs([]string{"test_config_files/test1.conf", "test_config_files/test2.conf", "test_config_files/test3.conf"})
	assert.Empty(t, err)
	assert.Equal(t, "5m", conf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))
	assert.Equal(t, "11m", conf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
	assert.EqualValues(t, 5, conf.GetInt32("cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second"))

	assert.Equal(t, "WARNING", conf.GetString("cloudflow.streamlets.cdr-aggregator.config.akka.loglevel"))

	conf, err = loadAndMergeConfigs([]string{"test_config_files/cdr-aggregator.conf", "test_config_files/test1.conf"})
	assert.Empty(t, err)
	assert.Equal(t, "2m", conf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))
	assert.Equal(t, "12m", conf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
}

func Test_addDefaultValues(t *testing.T) {

	conf, err := loadAndMergeConfigs([]string{"test_config_files/test1.conf", "test_config_files/test2.conf", "test_config_files/test3.conf"})
	assert.Empty(t, err)
	spec := cfapp.CloudflowApplicationSpec{
		Streamlets: []cfapp.Streamlet{
			{
				Descriptor: cfapp.Descriptor{
					ConfigParameters: []cfapp.ConfigParameterDescriptor{
						{
							Key:          "group-by-window",
							DefaultValue: "10m",
						},
						{
							Key:          "watermark",
							DefaultValue: "10m",
						},
					},
				},
				Name: "cdr-aggregator",
			},
		},
	}
	conf = addDefaultValuesFromSpec(spec, conf)
	assert.Equal(t, "5m", conf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))
	assert.Equal(t, "11m", conf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))

	conf, err = loadAndMergeConfigs([]string{"test_config_files/test1.conf"})
	assert.Empty(t, err)
	conf = addDefaultValuesFromSpec(spec, conf)
	assert.Equal(t, "10m", conf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))
	assert.Equal(t, "12m", conf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
}

func Test_addArguments(t *testing.T) {

	config := mergeWithFallback(
		configuration.LoadConfig("test_config_files/cdr-aggregator.conf"),
		configuration.LoadConfig("test_config_files/cdr-generator1.conf"),
	)

	spec := cfapp.CloudflowApplicationSpec{
		Streamlets: []cfapp.Streamlet{
			{
				Descriptor: cfapp.Descriptor{
					ConfigParameters: []cfapp.ConfigParameterDescriptor{
						{
							Key:          "group-by-window",
							DefaultValue: "10m",
							Type:         "duration",
						},
						{
							Key:          "watermark",
							DefaultValue: "10m",
							Type:         "duration",
						},
					},
				},
				Name: "cdr-aggregator",
			},
			{
				Descriptor: cfapp.Descriptor{
					ConfigParameters: []cfapp.ConfigParameterDescriptor{
						{
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

	configAdded := addCommandLineArguments(spec, config, map[string]string{
		"cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window":    "14m",
		"cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second": "100",
	})

	assert.EqualValues(t, 100, configAdded.GetInt32("cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second"))
	assert.Equal(t, "14m", configAdded.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
	assert.Equal(t, "2m", configAdded.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))

	config = mergeWithFallback(
		configuration.LoadConfig("test_config_files/cdr-aggregator.conf"),
		configuration.LoadConfig("test_config_files/cdr-generator1.conf"),
	)

	configAdded = addCommandLineArguments(spec, config, map[string]string{
		"cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window": "2m",
	})
	assert.Equal(t, "2m", configAdded.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
	assert.EqualValues(t, 8, configAdded.GetInt32("cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second"))
}
func Test_createStreamletConfigMap(t *testing.T) {
	spec := cfapp.CloudflowApplicationSpec{
		Streamlets: []cfapp.Streamlet{
			{
				Descriptor: cfapp.Descriptor{
					ConfigParameters: []cfapp.ConfigParameterDescriptor{
						{
							Key:          "group-by-window",
							DefaultValue: "10m",
							Type:         "duration",
						},
						{
							Key:          "watermark",
							DefaultValue: "10m",
							Type:         "duration",
						},
					},
					Runtime: "spark",
				},
				Name: "cdr-aggregator",
			},
			{
				Descriptor: cfapp.Descriptor{
					ConfigParameters: []cfapp.ConfigParameterDescriptor{
						{
							Key:          "records-per-second",
							DefaultValue: "10",
							Type:         "int32",
						},
					},
					Runtime: "spark",
				},
				Name: "cdr-generator1",
			},
		},
	}

	config := configuration.ParseString(`
	  cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window = "12m"
	  cloudflow.streamlets.cdr-aggregator.config {
      baz = fooz
		}
		cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second = 8
		cloudflow {
			runtimes {
				spark {
					config {
						foo = "bar"
					}
				}
			}
		}
	`)

	configs := createStreamletConfigsMap(spec, config)
	fmt.Println(configs)
	assert.Equal(t, "12m", configs["cdr-aggregator"].GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
	assert.EqualValues(t, 8, configs["cdr-generator1"].GetInt32("cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second"))
	assert.Equal(t, "bar", configs["cdr-aggregator"].GetString("cloudflow.streamlets.cdr-aggregator.config.foo"))
	assert.Equal(t, "fooz", configs["cdr-aggregator"].GetString("cloudflow.streamlets.cdr-aggregator.config.baz"))
	assert.Equal(t, "bar", configs["cdr-generator1"].GetString("cloudflow.streamlets.cdr-generator1.config.foo"))
}

func Test_validateConfigFiles(t *testing.T) {
	aggConf := configuration.LoadConfig("test_config_files/cdr-aggregator.conf")

	genConf := configuration.LoadConfig("test_config_files/cdr-generator1.conf")

	spec := cfapp.CloudflowApplicationSpec{
		Streamlets: []cfapp.Streamlet{
			{
				Descriptor: cfapp.Descriptor{
					ConfigParameters: []cfapp.ConfigParameterDescriptor{
						{
							Key:          "group-by-window",
							DefaultValue: "10m",
							Type:         "duration",
						},
						{
							Key:          "watermark",
							DefaultValue: "10m",
							Type:         "duration",
						},
					},
				},
				Name: "cdr-aggregator",
			},
			{
				Descriptor: cfapp.Descriptor{
					ConfigParameters: []cfapp.ConfigParameterDescriptor{
						{
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

	configs := map[string]*configuration.Config{
		"cdr-aggregator": aggConf,
		"cdr-generator1": genConf,
	}

	err := validateConfigurationAgainstDescriptor(spec, configs)
	assert.Empty(t, err)

	aggConf = configuration.LoadConfig("test_config_files/bad-cdr-aggregator.conf")
	configs = map[string]*configuration.Config{
		"cdr-aggregator": aggConf,
		"cdr-generator1": genConf,
	}
	err = validateConfigurationAgainstDescriptor(spec, configs)
	assert.NotEmpty(t, err)
}

func Test_validateConfig(t *testing.T) {
	spec := cfapp.CloudflowApplicationSpec{
		Streamlets: []cfapp.Streamlet{
			{
				Descriptor: cfapp.Descriptor{
					ConfigParameters: []cfapp.ConfigParameterDescriptor{
						{
							Key:          "my-parameter",
							DefaultValue: "10m",
							Type:         "duration",
						},
					},
				},
				Name: "my-streamlet",
			},
		},
	}

	noStreamletsOrRuntimes := configuration.ParseString(`
     a.b.c { }
  `)
	assert.NotEmpty(t, validateConfig(noStreamletsOrRuntimes, spec))

	unknownStreamletConfigSection := configuration.ParseString(`
     cloudflow.streamlets {
			 my-streamlet {
				 config-par = 1
			 }
		 }
	`)
	assert.NotEmpty(t, validateConfig(unknownStreamletConfigSection, spec))

	unknownRuntimeConfigSection := configuration.ParseString(`
     cloudflow.runtimes {
			 akka {
				 config-par = 1
			 }
		 }
	`)
	assert.NotEmpty(t, validateConfig(unknownRuntimeConfigSection, spec))

	validRuntimeConfigSection := configuration.ParseString(`
     cloudflow.runtimes {
			 akka {
				 config {
					 akka.loglevel = "WARNING"
				 }
				 kubernetes {
					 
				 }
			 }
		 }
	`)
	assert.Empty(t, validateConfig(validRuntimeConfigSection, spec))

	validStreamletConfigSection := configuration.ParseString(`
     cloudflow.streamlets {
			 my-streamlet {
				 config-parameters {
           my-parameter = "value"
				 }
				 kubernetes {

				 }
				 config {
					 akka.loglevel = "WARNING"
				 }
			 }
		 }
	`)
	assert.Empty(t, validateConfig(validStreamletConfigSection, spec))

	unknownConfigParameterInStreamletConfigSection := configuration.ParseString(`
     cloudflow.streamlets {
			 my-streamlet {
				 config-parameters {
					 my-parameter = "value"
					 my-par = "value"
				 }
				 kubernetes {

				 }
				 config {
					 akka.loglevel = "WARNING"
				 }
			 }
		 }
	`)
	assert.NotEmpty(t, validateConfig(unknownConfigParameterInStreamletConfigSection, spec))
}
func Test_moveToRootPath(t *testing.T) {

	config := configuration.ParseString(`
		my-streamlet {
			config-parameters {
				my-parameter = "value"
			}
			kubernetes {

			}
			config {
				akka.loglevel = "WARNING"
			}
		}
	`)

	config = moveToRootPath(config, "cloudflow.streamlets")
	println(config.String())
	assert.Equal(t, "value", config.GetString("cloudflow.streamlets.my-streamlet.config-parameters.my-parameter"))
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

func Test_ValidationOfDuration(t *testing.T) {

	assert.NoError(t, validateDuration("300ms"))
	assert.NoError(t, validateDuration("300 ns"))
	assert.NoError(t, validateDuration("300 nano"))
	assert.NoError(t, validateDuration("300 nanos"))
	assert.NoError(t, validateDuration("300 nanosecond"))
	assert.NoError(t, validateDuration("300 nanoseconds"))
	assert.NoError(t, validateDuration("300 us"))
	assert.NoError(t, validateDuration("300 micro"))
	assert.NoError(t, validateDuration("300 micros"))
	assert.NoError(t, validateDuration("300 microsecond"))
	assert.NoError(t, validateDuration("300 microseconds"))
	assert.NoError(t, validateDuration("300 ms"))
	assert.NoError(t, validateDuration("300 milli"))
	assert.NoError(t, validateDuration("300 millis"))
	assert.NoError(t, validateDuration("300 millisecond"))
	assert.NoError(t, validateDuration("300 milliseconds"))
	assert.NoError(t, validateDuration("300 s"))
	assert.NoError(t, validateDuration("300 second"))
	assert.NoError(t, validateDuration("300 seconds"))
	assert.NoError(t, validateDuration("300 m"))
	assert.NoError(t, validateDuration("300 minute"))
	assert.NoError(t, validateDuration("300 minutes"))
	assert.NoError(t, validateDuration("300 h"))
	assert.NoError(t, validateDuration("300 hour"))
	assert.NoError(t, validateDuration("300 hours"))
	assert.NoError(t, validateDuration("300 d"))
	assert.NoError(t, validateDuration("300 day"))
	assert.NoError(t, validateDuration("300 days"))

	assert.Error(t, validateDuration("300 parsec"))

	assert.Error(t, validateDuration(" seconds"))
	assert.Error(t, validateDuration(" "))
	assert.Error(t, validateDuration("100 seconds 200"))

	assert.NoError(t, validateDuration("100"))

	assert.Error(t, validateDuration("e100"))
}

func Test_ValidationOfMemorySize(t *testing.T) {

	assert.NoError(t, validateMemorySize("300 B"))
	assert.NoError(t, validateMemorySize("300B"))
	assert.NoError(t, validateMemorySize("300 b"))
	assert.NoError(t, validateMemorySize("300 byte"))
	assert.NoError(t, validateMemorySize("300byte"))
	assert.NoError(t, validateMemorySize("300 bytes"))
	assert.NoError(t, validateMemorySize("300 kB"))
	assert.NoError(t, validateMemorySize("300 kilobyte"))
	assert.NoError(t, validateMemorySize("300 kilobytes"))
	assert.NoError(t, validateMemorySize("300 MB"))
	assert.NoError(t, validateMemorySize("300 megabyte"))
	assert.NoError(t, validateMemorySize("300 megabytes"))
	assert.NoError(t, validateMemorySize("300 GB"))
	assert.NoError(t, validateMemorySize("300 gigabyte"))
	assert.NoError(t, validateMemorySize("300 gigabytes"))
	assert.NoError(t, validateMemorySize("300 TB"))
	assert.NoError(t, validateMemorySize("300 terabyte"))
	assert.NoError(t, validateMemorySize("300 terabytes"))
	assert.NoError(t, validateMemorySize("300 PB"))
	assert.NoError(t, validateMemorySize("300 petabyte"))
	assert.NoError(t, validateMemorySize("300 petabytes"))
	assert.NoError(t, validateMemorySize("300 EB"))
	assert.NoError(t, validateMemorySize("300 exabyte"))
	assert.NoError(t, validateMemorySize("300 exabytes"))
	assert.NoError(t, validateMemorySize("300 ZB"))
	assert.NoError(t, validateMemorySize("300 zettabyte"))
	assert.NoError(t, validateMemorySize("300 zettabytes"))
	assert.NoError(t, validateMemorySize("300 YB"))
	assert.NoError(t, validateMemorySize("300 yottabyte"))
	assert.NoError(t, validateMemorySize("300 yottabytes"))
	assert.NoError(t, validateMemorySize("300 K"))
	assert.NoError(t, validateMemorySize("300 k"))
	assert.NoError(t, validateMemorySize("300 Ki"))
	assert.NoError(t, validateMemorySize("300 KiB"))
	assert.NoError(t, validateMemorySize("300 kibibyte"))
	assert.NoError(t, validateMemorySize("300 kibibytes"))
	assert.NoError(t, validateMemorySize("300 M"))
	assert.NoError(t, validateMemorySize("300 m"))
	assert.NoError(t, validateMemorySize("300 Mi"))
	assert.NoError(t, validateMemorySize("300 MiB"))
	assert.NoError(t, validateMemorySize("300 mebibyte"))
	assert.NoError(t, validateMemorySize("300 mebibytes"))
	assert.NoError(t, validateMemorySize("300 G"))
	assert.NoError(t, validateMemorySize("300 g"))
	assert.NoError(t, validateMemorySize("300 Gi"))
	assert.NoError(t, validateMemorySize("300 GiB"))
	assert.NoError(t, validateMemorySize("300 gibibyte"))
	assert.NoError(t, validateMemorySize("300 gibibytes"))
	assert.NoError(t, validateMemorySize("300 T"))
	assert.NoError(t, validateMemorySize("300 t"))
	assert.NoError(t, validateMemorySize("300 Ti"))
	assert.NoError(t, validateMemorySize("300 TiB"))
	assert.NoError(t, validateMemorySize("300 tebibyte"))
	assert.NoError(t, validateMemorySize("300 tebibytes"))
	assert.NoError(t, validateMemorySize("300 P"))
	assert.NoError(t, validateMemorySize("300 p"))
	assert.NoError(t, validateMemorySize("300 Pi"))
	assert.NoError(t, validateMemorySize("300 PiB"))
	assert.NoError(t, validateMemorySize("300 pebibyte"))
	assert.NoError(t, validateMemorySize("300 pebibytes"))
	assert.NoError(t, validateMemorySize("300 E"))
	assert.NoError(t, validateMemorySize("300 e"))
	assert.NoError(t, validateMemorySize("300 Ei"))
	assert.NoError(t, validateMemorySize("300 EiB"))
	assert.NoError(t, validateMemorySize("300 exbibyte"))
	assert.NoError(t, validateMemorySize("300 exbibytes"))
	assert.NoError(t, validateMemorySize("300 Z"))
	assert.NoError(t, validateMemorySize("300 z"))
	assert.NoError(t, validateMemorySize("300 Zi"))
	assert.NoError(t, validateMemorySize("300 ZiB"))
	assert.NoError(t, validateMemorySize("300 zebibyte"))
	assert.NoError(t, validateMemorySize("300 zebibytes"))
	assert.NoError(t, validateMemorySize("300 Y"))
	assert.NoError(t, validateMemorySize("300 y"))
	assert.NoError(t, validateMemorySize("300 Yi"))
	assert.NoError(t, validateMemorySize("300 YiB"))
	assert.NoError(t, validateMemorySize("300 yobibyte"))
	assert.NoError(t, validateMemorySize("300 yobibytes"))

	assert.Error(t, validateMemorySize("300 parsec"))

	assert.Error(t, validateMemorySize(" exbi"))
	assert.Error(t, validateMemorySize(" "))
	assert.Error(t, validateMemorySize("100 exbi 200"))
}
