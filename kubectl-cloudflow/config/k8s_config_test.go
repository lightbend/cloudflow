package config

import (
	"fmt"
	"testing"

	"github.com/stretchr/testify/assert"
)

func Test_validateConfigLabels(t *testing.T) {
	spec := createSpec()
	labelConfigSection := newConfig(`
	cloudflow.streamlets.my-streamlet.kubernetes.pods.pod {
		labels {
			key1 = value1
			key2 = value2
		}
	}
	`)
	assert.Empty(t, validateConfig(labelConfigSection, spec))

	labelConfigSectionUppercase := newConfig(`
	cloudflow.streamlets.my-streamlet.kubernetes.pods.pod {
		labels {
			KEY1 = VALUE1 
		}
	}
	`)
	assert.Empty(t, validateConfig(labelConfigSectionUppercase, spec))

	labelConfigSectionPrefix := newConfig(`
	cloudflow.streamlets.my-streamlet.kubernetes.pods.pod {
		labels {
			subdomain123/KEY1 = VALUE1 
		}
	}
	`)
	assert.Empty(t, validateConfig(labelConfigSectionPrefix, spec))

	badLabelConfigSectionPrefix := newConfig(`
	cloudflow.streamlets.my-streamlet.kubernetes.pods.pod {
		labels {
			SUBDOMAIN123/KEY1 = VALUE1 
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelConfigSectionPrefix, spec))
	fmt.Printf("badLabelConfigSectionPrefix: %s\n", validateConfig(badLabelConfigSectionPrefix, spec))

	badLabelConfigValueEmpty := newConfig(`
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
			}
			labels {
				key1
			}
		}	
		job-manager {
			containers.container {
				resources {
					requests {
						cpu = 2
						memory = "512M"
					}
					limits {
						memory = "1024M"
					}
				}
			}
			labels {
				key2: value2
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(badLabelConfigValueEmpty, spec))
	fmt.Printf("badLabelConfigValueEmpty: %s\n", validateConfig(badLabelConfigValueEmpty, spec))

	badLabelConfigSectionEmpty2 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		containers.container {
			resources {
				requests {
					cpu=2
					memory = "512M"
				}
				limits {
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

	badLabelKeyTooLong := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		containers.container {
			resources {
				requests {
					cpu = 2
					memory = "512M"
				}
				limits {
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
				}
				limits {
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
				}
				limits {
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
				}
				limits {
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
				}
				limits {
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
				}
				limits {
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
				}
				limits {
					memory = "1024M"
				}
			}
		}
	}
	`)
	assert.Empty(t, validateConfig(okShortLabel, spec))

}

func Test_validateConfigVolumes(t *testing.T) {
	spec := createSpec()

	volumePodWellformed := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {}
		volumes {
			foo {
				secret {
					name = mysecret
				}
			}
			bar {
				secret {
					name = yoursecret
				}
			}
		}
	}
	`)
	assert.Empty(t, validateConfig(volumePodWellformed, spec))

	volumePodMalformed := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {}
		volumes {
			foo {
				secret {
					name 
				}
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(volumePodMalformed, spec))
	fmt.Printf("volumePodMalformed: %s\n", validateConfig(volumePodMalformed, spec))

	volumePodMalformed2 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {}
		volumes {
			foo {
				secret {
				}
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(volumePodMalformed2, spec))
	fmt.Printf("volumePodMalformed2: %s\n", validateConfig(volumePodMalformed2, spec))

	volumePodMalformed3 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {}
		volumes {
			foo {
				secret {
				}
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(volumePodMalformed3, spec))
	fmt.Printf("volumePodMalformed3: %s\n", validateConfig(volumePodMalformed3, spec))

	volumePodMalformed4 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {}
		volumes {
			foo {
				secret = mymalformedsecret
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(volumePodMalformed4, spec))
	fmt.Printf("volumePodMalformed4: %s\n", validateConfig(volumePodMalformed4, spec))

	volumePodMalformed5 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {}
		volumes {
			foo {
				pvc = mymalformedpvc
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(volumePodMalformed5, spec))
	fmt.Printf("volumePodMalformed5: %s\n", validateConfig(volumePodMalformed5, spec))

	volumeMountPodWellformed := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		volumes {
			foo {
				secret {
					name = mysecret
				}
			}
			bar {
				secret {
					name = yoursecret
				}
			}
		}
		containers.container {
			volume-mounts {
				foo {
					mount-path = "/etc/my/file"
					read-only = true
				}
				bar {
					mount-path = "/etc/mc/fly"
					read-only = false
				}
			}
		}
	}
	`)
	assert.Empty(t, validateConfig(volumeMountPodWellformed, spec))

	volumeMountPodWellformed2 := newConfig(`
	cloudflow.runtimes.spark.kubernetes.pods {
		pod {
		  	volumes {
			    foo {
				    secret {
				    	name = mysecret
				    } 
			    }
			    bar {
				    secret {
				        name = myothersecret
				    }
			    }
		  } 
		}

		driver {
		  	containers.container {
			    volume-mounts {
			      	foo {
			        	mount-path: "/tmp/some"
			      	}
			    }
		  }
		}

		executor {
			containers.container {
			    volume-mounts {
				    bar {
				        mount-path: "/tmp/some"
				    }
			    }
			}
		}
	}`)
	assert.Empty(t, validateConfig(volumeMountPodWellformed2, spec))

	volumeMountPodWellformed3 := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		volumes {
			foo {
				pvc {
					name = myclaim
					read-only = true
				}
			}
			bar {
				secret {
					name = yoursecret
				}
			}
		}
		containers.container {
			volume-mounts {
				foo {
					mount-path = "/etc/my/file"
					read-only = true
				}
				bar {
					mount-path = "/etc/mc/fly"
					read-only = false
				}
			}
		}
	}
	`)
	assert.Empty(t, validateConfig(volumeMountPodWellformed3, spec))

	volumeMountPodMalformed := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		labels {}
		containers.container {
			volume-mounts {
				foo {
					mount-pathy = "/etc/my/file"
				}
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(volumeMountPodMalformed, spec))
	fmt.Printf("volumeMountPodMalformed: %s\n", validateConfig(volumeMountPodMalformed, spec))

	secretsVolumeMountMismatch := newConfig(`
	cloudflow.runtimes.flink.kubernetes.pods.pod {
		volumes {
			foo {
				secret {
					name = mysecret
				}
			}
		}
		containers.container {
			volume-mounts {
				foo {
					mount-path = "/etc/my/file"
					read-only = true
				}
				bar {
					mount-path = "/etc/mc/fly"
					read-only = false
				}
			}
		}
	}
	`)
	assert.NotEmpty(t, validateConfig(secretsVolumeMountMismatch, spec))
	fmt.Printf("secretsVolumeMountMismatch: %s\n", validateConfig(secretsVolumeMountMismatch, spec))
}
func Test_validateConfigPaths(t *testing.T) {
	spec := createSpec()

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

func Test_validateConfigTopic(t *testing.T) {
	spec := createSpec()
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
}
