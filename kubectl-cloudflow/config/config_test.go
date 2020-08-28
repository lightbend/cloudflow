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
	result, err := splitConfigurationParameters([]string{
		`a="b"`,
		"key=ddd",
		`some.key="some, value"`,
		`some.key2=c29tZSBzdHJpbmc9PQ==`,
		`some.key3="some text passed to a streamlet"`,
		`some.key4=post Cobra processed, unquoted string`})
	assert.Empty(t, err)
	assert.NotEmpty(t, result)
	assert.Equal(t, result["a"], "b")
	assert.Equal(t, result["key"], "ddd")
	assert.Equal(t, result["some.key"], "some, value")
	assert.Equal(t, result["some.key2"], "c29tZSBzdHJpbmc9PQ==")
	assert.Equal(t, result["some.key3"], "some text passed to a streamlet")
	assert.Equal(t, result["some.key4"], "post Cobra processed, unquoted string")

	empty, err := splitConfigurationParameters([]string{})
	assert.Empty(t, err)
	assert.Empty(t, empty)
}

func Test_validateConfigurationAgainstDescriptor(t *testing.T) {

	applicationConfiguration := cfapp.TestApplicationDescriptor()

	var spec cfapp.CloudflowApplicationSpec
	json.Unmarshal([]byte(applicationConfiguration), &spec)

	config := new(Config)
	err := validateConfigurationAgainstDescriptor(spec, *config)
	assert.NotEmpty(t, err)

	args, err := splitConfigurationParameters(commandLineForConfiguration())
	assert.Empty(t, err)

	config = addCommandLineArguments(spec, config, args)
	err = validateConfigurationAgainstDescriptor(spec, *config)

	assert.Empty(t, err)

	half, err := splitConfigurationParameters([]string{`cloudflow.streamlets.valid-logger.config-parameters.log-level="warning"`})
	assert.Empty(t, err)

	config = new(Config)
	config = addCommandLineArguments(spec, config, half)
	err = validateConfigurationAgainstDescriptor(spec, *config)
	assert.NotEmpty(t, err)
}

func Test_CreateSecret(t *testing.T) {
	applicationConfiguration := cfapp.TestApplicationDescriptor()

	var spec cfapp.CloudflowApplicationSpec
	json.Unmarshal([]byte(applicationConfiguration), &spec)

	args, err := splitConfigurationParameters(commandLineForConfiguration())
	assert.Empty(t, err)

	config := new(Config)
	config = addCommandLineArguments(spec, config, args)
	hoconConfig := configuration.ParseString(config.String())
	secret, err := createAppInputSecret(&spec, config)
	assert.Empty(t, err)
	assert.NotEmpty(t, secret)
	hoconConfig = configuration.ParseString(secret.StringData["secret.conf"])
	assert.Equal(t, "warning", hoconConfig.GetString("cloudflow.streamlets.valid-logger.config-parameters.log-level"))
	assert.Equal(t, "test", hoconConfig.GetString("cloudflow.streamlets.valid-logger.config-parameters.msg-prefix"))
}

func Test_loadAndMergeConfigs(t *testing.T) {
	config, err := loadAndMergeConfigs([]string{"non-existing.conf", "non-existing.conf"})
	assert.NotEmpty(t, err)

	_, err = loadAndMergeConfigs([]string{"non-existing.conf", "test_config_files/test1.conf"})
	assert.NotEmpty(t, err)

	config, err = loadAndMergeConfigs([]string{"test_config_files/test1.conf", "test_config_files/test2.conf"})
	hoconConfig := configuration.ParseString(config.String())

	assert.Empty(t, err)
	assert.Equal(t, "5m", hoconConfig.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))
	assert.Equal(t, "12m", hoconConfig.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
	assert.EqualValues(t, 5, hoconConfig.GetInt32("cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second"))

	config, err = loadAndMergeConfigs([]string{"test_config_files/test1.conf", "test_config_files/test2.conf", "test_config_files/test3.conf"})
	hoconConfig = configuration.ParseString(config.String())
	assert.Empty(t, err)
	assert.Equal(t, "5m", hoconConfig.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))
	assert.Equal(t, "11m", hoconConfig.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
	assert.EqualValues(t, 5, hoconConfig.GetInt32("cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second"))

	assert.Equal(t, "WARNING", hoconConfig.GetString("cloudflow.streamlets.cdr-aggregator.config.akka.loglevel"))

	config, err = loadAndMergeConfigs([]string{"test_config_files/cdr-aggregator.conf", "test_config_files/test1.conf"})
	hoconConfig = configuration.ParseString(config.String())
	assert.Empty(t, err)
	assert.Equal(t, "2m", hoconConfig.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))
	assert.Equal(t, "12m", hoconConfig.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
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
	conf = addDefaultValuesFromSpec(spec, conf, map[string]string{})
	hoconConf := configuration.ParseString(conf.String())
	assert.Equal(t, "5m", hoconConf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))
	assert.Equal(t, "11m", hoconConf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))

	conf, err = loadAndMergeConfigs([]string{"test_config_files/test1.conf"})
	assert.Empty(t, err)
	conf = addDefaultValuesFromSpec(spec, conf, map[string]string{})
	hoconConf = configuration.ParseString(conf.String())
	assert.Equal(t, "10m", hoconConf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))
	assert.Equal(t, "12m", hoconConf.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
}

func Test_addCommandLineArguments(t *testing.T) {

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

	config, err := loadAndMergeConfigs([]string{"test_config_files/cdr-aggregator.conf", "test_config_files/cdr-generator1.conf"})
	assert.Empty(t, err)

	configAdded := addCommandLineArguments(spec, config, map[string]string{
		"cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window":    "14m",
		"cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second": "100",
	})
	hoconConfig := configuration.ParseString(configAdded.String())
	assert.EqualValues(t, 100, hoconConfig.GetInt32("cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second"))
	assert.Equal(t, "14m", hoconConfig.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
	assert.Equal(t, "2m", hoconConfig.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.watermark"))

	config, err = loadAndMergeConfigs([]string{"test_config_files/cdr-aggregator.conf", "test_config_files/cdr-generator1.conf"})
	assert.Empty(t, err)

	configAdded = addCommandLineArguments(spec, config, map[string]string{
		"cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window": "2m",
	})

	hoconConfig = configuration.ParseString(configAdded.String())

	assert.Equal(t, "2m", hoconConfig.GetString("cloudflow.streamlets.cdr-aggregator.config-parameters.group-by-window"))
	assert.EqualValues(t, 8, hoconConfig.GetInt32("cloudflow.streamlets.cdr-generator1.config-parameters.records-per-second"))
}

func Test_validateConfigFiles(t *testing.T) {
	config, err := loadAndMergeConfigs([]string{"test_config_files/cdr-aggregator.conf", "test_config_files/cdr-generator1.conf"})
	assert.Empty(t, err)

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

	err = validateConfigurationAgainstDescriptor(spec, *config)
	assert.Empty(t, err)

	config, err = loadAndMergeConfigs([]string{"test_config_files/bad-cdr-aggregator.conf"})
	err = validateConfigurationAgainstDescriptor(spec, *config)
	assert.NotEmpty(t, err)
}
func createSpec() cfapp.CloudflowApplicationSpec {
	return cfapp.CloudflowApplicationSpec{
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
		Deployments: []cfapp.Deployment{
			{
				PortMappings: map[string]cfapp.PortMapping{
					"port": {
						ID: "my-topic",
					},
				},
			},
		},
	}
}
func Test_validateConfig(t *testing.T) {
	spec := createSpec()
	noStreamletsOrRuntimes := newConfig("a.b.c { }")
	assert.NotEmpty(t, validateConfig(noStreamletsOrRuntimes, spec))
	fmt.Printf("noStreamletsOrRuntimes: %s\n", validateConfig(noStreamletsOrRuntimes, spec))

	unknownStreamletConfigSection := newConfig(`
	cloudflow.streamlets {
		my-streamlet {
			config-par = 1
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(unknownStreamletConfigSection, spec))
	fmt.Printf("unknownStreamletConfigSection: %s\n", validateConfig(unknownStreamletConfigSection, spec))

	labelConfigSection := newConfig(`
	cloudflow.streamlets.my-streamlet.kubernetes.pods.pod {
		labels {
			key1 = value1
			key2 = value2
		} containers.container {
			resources {
				requests {
					cpu = 2
					memory = "512M"
				}limits {
					memory = "1024M"
				}
			}
		}
	}
	`)

	assert.Empty(t, validateConfig(labelConfigSection, spec))

	badLabelConfigSectionEmpty := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods {
		task-manager {
			containers.container {
				resources {
					requests {
						cpu = 2
						memory = "512M"
					}limits {
						memory = "1024M"
					}
				}
			}labels {
				key1
			}
		}job-manager {
			containers.container {
				resources {
					requests {
						cpu = 2
						memory = "512M"
					}limits {
						memory = "1024M"
					}
				}
			}labels {
				key2: value2
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelConfigSectionEmpty, spec))
	fmt.Printf("badLabelConfigSectionEmpty: %s\n", validateConfig(badLabelConfigSectionEmpty, spec))

	badLabelConfigSectionEmpty2 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		containers.container {
			resources {
				requests {
					cpu=2
					memory = "512M"
				}limits {
					memory = "1024M"
				}
			}
		}
		labels {
			key1
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelConfigSectionEmpty2, spec))
	fmt.Printf("badLabelConfigSectionEmpty2: %s\n", validateConfig(badLabelConfigSectionEmpty2, spec))

	badLabelKeyTooLong := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		containers.container {
			resources {
				requests {
					cpu = 2
					memory = "512M"
				}limits {
					memory = "1024M"
				}
			}
		}
		labels {
			keyabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz: value2
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelKeyTooLong, spec))
	fmt.Printf("badLabelKeyTooLong: %s\n", validateConfig(badLabelKeyTooLong, spec))

	badLabelKeyMalformed := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		containers.container {
			resources {
				requests {
					cpu = 2
					memory = "512M"
				}limits {
					memory = "1024M"
				}
			}
		}
		labels {
			"keyabcdefstuv+zabcdefghijklmnopqrstuvwxyz": value2
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelKeyMalformed, spec))
	fmt.Printf("badLabelKeyMalformed: %s\n", validateConfig(badLabelKeyMalformed, spec))

	badLabelKeyMalformed2 := newConfig(`
  cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {
		 	"lkjsdfsdf..sdfsfd//keyabcdefstuvzabcdefghijklmnopqrstuvwxyz" :  value2
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelKeyMalformed2, spec))
	fmt.Printf("badLabelKeyMalformed2: %s\n", validateConfig(badLabelKeyMalformed2, spec))

	badLabelKeyMalformed3 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		containers.container {
			resources {
				requests {
					cpu=2
					memory = "512M"
				}limits {
					memory = "1024M"
				}
			}
		}
		labels {
			"lkjsdfsdfsdfs+fd/keyabcdefstuvzabcdefghijklmnopqrstuvwxyz": value2
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelKeyMalformed3, spec))
	fmt.Printf("badLabelKeyMalformed3: %s\n", validateConfig(badLabelKeyMalformed3, spec))

	badLabelKeyPrefixMalformed := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		containers.container {
			resources {
				requests {
					cpu=2
					memory = "512M"
				}limits {
					memory = "1024M"
				}
			}
		}
		labels {
			"0lkjsdfsdfsdfsfd/keyabcdefstuvzabcdefghijklmnopqrstuvwxyz": value2
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelKeyPrefixMalformed, spec))
	fmt.Printf("badLabelKeyPrefixMalformed: %s\n", validateConfig(badLabelKeyPrefixMalformed, spec))

	badLabelKeyPrefixTooLong := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		containers.container {
			resources {
				requests {
					cpu=2
					memory = "512M"
				}limits {
					memory = "1024M"
				}
			}
		}labels {
			"flkjsdfsdfsdfsfdkeyabcdefstuvzabcdefghijklmnopqrstuvwxyzkeyghijklmnopqrstuvwxyzkeyghijklmnopqrstuvwxyzkeyghijklmnopqrstuvwxyzkeyghijklmnopqrstuvwxyzkeyghijklmnopqrstuvwxyzkeyabcdefstuvzabcdefghijklmnopqrstuvwxyzkeyabcdefstuvzabcdefghijklmnopqrstuvwxyzkeyabcdefstuvzabcdefghijklmnopqrstuvwxyz/keyabcdefstuvzabcdefghijklmnopqrstuvwxyz": value2
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelKeyPrefixTooLong, spec))
	fmt.Printf("badLabelKeyPrefixTooLong: %s\n", validateConfig(badLabelKeyPrefixTooLong, spec))

	labelKeyWellFormed2 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		containers.container {
			resources {
				requests {
					cpu = 2
					memory = "512M"
				}limits {
					memory = "1024M"
				}
			}
		}
		labels {
			"lkjsdfsdfsdfsfd/keyabcdefstuvzabcdefghijklmnopqrstuvwxyz": value2
		}
	}	
	`)
	assert.Empty(t, validateConfig(labelKeyWellFormed2, spec))

	badLabelValueMalformed := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {
		  key1 : "stuvwxyzabcde*fghijkl*mnopqrstuvwxyz"
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelValueMalformed, spec))
	fmt.Printf("badLabelValueMalformed: %s\n", validateConfig(badLabelValueMalformed, spec))

	badLabelKeyMalformed22 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {
		  "/key1" : "stuvwxyzabcdefghijklmnopqrstuvwxyz"
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelKeyMalformed22, spec))
	fmt.Printf("badLabelKeyMalformed22: %s\n", validateConfig(badLabelKeyMalformed22, spec))

	badLabelKeyMalformed33 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
	  labels {
		  "key1/" : "stuvwxyzabcde*fghijkl*mnopqrstuvwxyz"
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelKeyMalformed33, spec))
	fmt.Printf("badLabelKeyMalformed33: %s\n", validateConfig(badLabelKeyMalformed33, spec))

	badLabelValueMalFormed4 := newConfig(`
  cloudflow.runtimes.flink.kubernetes.pods.pod {
	  labels {
		  "/k" : "stuvwxyzarstuvwxyz"
		}
  }
	`)
	assert.NotEmpty(t, validateConfig(badLabelValueMalFormed4, spec))
	fmt.Printf("badLabelValueMalFormed4: %s\n", validateConfig(badLabelValueMalFormed4, spec))

	badLabelValueMalFormed5 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {
		  "h/" : "stuvwxyzarstuvwxyz"
		}
  }
	`)
	assert.NotEmpty(t, validateConfig(badLabelValueMalFormed5, spec))
	fmt.Printf("badLabelValueMalFormed5: %s\n", validateConfig(badLabelValueMalFormed5, spec))

	// TODO: the validation now does not allow to just provide labels, containers is seen as mandatory
	okShortLabel := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {
			a = b
		}
		containers.container {
			resources {
				requests {
					cpu = 2
					memory = "512M"
				}limits {
					memory = "1024M"
				}
			}
		}
	}
	`)
	assert.Empty(t, validateConfig(okShortLabel, spec))
	fmt.Printf("okShortLabel: %s\n", validateConfig(okShortLabel, spec))

	unknownRuntimeConfigSection := newConfig(`
	cloudflow.runtimes {
		akka {
			config-par = 1
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(unknownRuntimeConfigSection, spec))

	validRuntimeConfigSection := newConfig(`
	cloudflow.runtimes {
		akka {
			config {
				akka.loglevel = "WARNING"
			}
		}
	}
	`)
	assert.Empty(t, validateConfig(validRuntimeConfigSection, spec))

	validStreamletConfigSection := newConfig(`
	cloudflow.streamlets {
		my-streamlet {
			config-parameters {
							my-parameter = "value"
			}
			config {
				akka.loglevel = "WARNING"
			}
		}
	}
	`)
	assert.Empty(t, validateConfig(validStreamletConfigSection, spec))

	unknownConfigParameterInStreamletConfigSection := newConfig(`
	cloudflow.streamlets {
		my-streamlet {
			config-parameters {
				my-parameter = "value"
				my-par = "value"
			}
			config {
				akka.loglevel = "WARNING"
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(unknownConfigParameterInStreamletConfigSection, spec))
	fmt.Printf("unknownConfigParameterInStreamletConfigSection: %s\n", validateConfig(unknownConfigParameterInStreamletConfigSection, spec))

	validTopic := newConfig(`
	cloudflow.topics {
		my-topic {
				topic.name = "my-topic-name"
		}
	}
	`)
	assert.Empty(t, validateConfig(validTopic, spec))
	unknownTopic := newConfig(`
	cloudflow.topics {
		topic {
				topic.name = "my-topic-name"
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(unknownTopic, spec))

	badK8sPath := newConfig(`
	   cloudflow.streamlets.my-streamlet.kubernetes.pods.pod.containers.resources.requests.memory = "256M"
	`)
	assert.NotEmpty(t, validateConfig(badK8sPath, spec))
	fmt.Printf("badK8sPath: %s\n", validateConfig(badK8sPath, spec))

	badK8sPath2 := newConfig(`
	   cloudflow.streamlets.my-streamlet.kubernetes.pods.requests.memory = "256M"
	`)
	assert.NotEmpty(t, validateConfig(badK8sPath2, spec))
	fmt.Printf("badK8sPath2: %s\n", validateConfig(badK8sPath2, spec))

	badK8sPath3 := newConfig(`
	   cloudflow.streamlets.my-streamlet.kubernetes.pods.containers.requests.memory = "256M"
	`)
	assert.NotEmpty(t, validateConfig(badK8sPath3, spec))
	fmt.Printf("badK8sPath3: %s\n", validateConfig(badK8sPath3, spec))
}

func Test_labelValueTooLong(t *testing.T) {
	spec := createSpec()
	badLabelValueTooLong := newConfig(`

  cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {
		 	key1 = valueabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelValueTooLong, spec))
	fmt.Printf("badLabelValueTooLong: %s\n", validateConfig(badLabelValueTooLong, spec))
}
func Test_labelValueMalformed(t *testing.T) {
	spec := createSpec()
	labelKeyMalformed := newConfig(`
  cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {
		 	keyabcdefstuv="jzabcdefghijklmnopqrstuvwxyz : value2"
		}
	}
	`)

	assert.NotEmpty(t, validateConfig(labelKeyMalformed, spec))
	fmt.Printf("labelKeyMalformed: %s\n", validateConfig(labelKeyMalformed, spec))
}

func Test_labelTooSpecificForFlink(t *testing.T) {
	spec := createSpec()
	badLabelTooSpecific := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods{
		task-manager{
			labels {
				key1: value1
			}
		}
		job-manager{
			labels {
				key1: value1
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelTooSpecific, spec))
	// more info in https://github.com/lyft/flinkk8soperator/blob/master/pkg/apis/app/v1beta1/types.go
	// metav1.ObjectMeta only exists in type `FlinkApplication` not in `TaskManagerConfig` nor `JobManagerConfig`
	fmt.Printf("badLabelTooSpecific: %s\n", validateConfig(badLabelTooSpecific, spec))
}

func Test_validateConfigEmptyDefault(t *testing.T) {
	spec := cfapp.CloudflowApplicationSpec{
		Streamlets: []cfapp.Streamlet{
			{
				Descriptor: cfapp.Descriptor{
					ConfigParameters: []cfapp.ConfigParameterDescriptor{
						{
							Key:          "my-parameter",
							DefaultValue: "",
							Type:         "string",
						},
					},
				},
				Name: "my-streamlet",
			},
		},
		Deployments: []cfapp.Deployment{
			{
				PortMappings: map[string]cfapp.PortMapping{
					"port": {
						ID: "my-topic",
					},
				},
			},
		},
	}

	validStreamletConfigSection := newConfig("")
	assert.Empty(t, validateConfigurationAgainstDescriptor(spec, *validStreamletConfigSection))
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
