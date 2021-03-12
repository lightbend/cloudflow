/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cloudflow.config

import java.io.File
import scala.jdk.CollectionConverters._
import scala.annotation.nowarn
import akka.datap.crd.App
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.ConfigFactory
import io.fabric8.kubernetes.client.utils.Serialization
import org.scalatest.{ OptionValues, TryValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class CloudflowConfigSpec extends AnyFlatSpec with Matchers with OptionValues with TryValues {
  import CloudflowConfig._

  "The CloudflowConfig" should "parse accordingly a demo configuration" in {
    // Arrange
    @nowarn val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      config-parameters {
                  |        // config parameter values go here
                  |        my-config-parameter = "some-value"
                  |        another-config-parameter = "default-value"
                  |        another-config-parameter = ${?MY_VAR}
                  |      }
                  |      config {
                  |        // runtime settings go here
                  |        akka.loglevel = "DEBUG"
                  |      }
                  |      kubernetes {
                  |        pods.pod.containers.container {
                  |          // kubernetes container settings go here
                  |          resources {
                  |            requests {
                  |              memory = "512M"
                  |            }
                  |            limits {
                  |              memory = "1024M"
                  |            }
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isSuccess shouldBe true
    res.get.cloudflow.streamlets.nonEmpty shouldBe true
    val myStreamlet = res.get.cloudflow.streamlets("my-streamlet")
    myStreamlet.configParameters.getString("my-config-parameter") shouldBe "some-value"
  }

  it should "fail with a clear error on empty streamlets" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |    }
                  |  }
                  |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failed.get.getMessage.contains(MandatorySectionsText) shouldBe true
  }

  it should "fail on extra keys" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      extra = spurious
                  |      kubernetes.pods.pod {
                  |        volumes {
                  |          foo {
                  |            secret {
                  |              name = mysecret
                  |            }
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}
                  |""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failed.get.getMessage.contains("Unknown key") shouldBe true
  }

  it should "fail on extra keys in custom readers" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      kubernetes.pods.pod {
                  |        volumes {
                  |          foo {
                  |            secret {
                  |              name = mysecret
                  |              extra = spurious
                  |            }
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}
                  |""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failed.get.getMessage.contains("Unknown key") shouldBe true
  }

  it should "fail on unknown volumes" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      kubernetes.pods.pod {
                  |        volumes {
                  |          foo {
                  |            bar {
                  |              name = mysecret
                  |            }
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}
                  |""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failed.get.getMessage.contains("Cannot convert 'bar' to Volume") shouldBe true
  }

  it should "parse properly the volumes in the kubernetes section" in {
    // Arrange
    val config = """cloudflow {
                   |  streamlets {
                   |    my-streamlet {
                   |      kubernetes.pods.pod {
                   |        volumes {
                   |          foo {
                   |            secret {
                   |              name = mysecret
                   |            }
                   |          },
                   |          bar {
                   |            pvc {
                   |              name = "/etc/my/file"
                   |              read-only = true
                   |            }
                   |          }
                   |        }
                   |      }
                   |    }
                   |  }
                   |}
                   |""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    val pod = res.get.cloudflow.streamlets("my-streamlet").kubernetes.pods("pod")
    pod.volumes.nonEmpty shouldBe true
    pod.volumes("foo").isInstanceOf[SecretVolume] shouldBe true
    pod.volumes("foo").asInstanceOf[SecretVolume].name shouldBe "mysecret"
    pod.volumes("bar").isInstanceOf[PvcVolume] shouldBe true
    pod.volumes("bar").asInstanceOf[PvcVolume].name shouldBe "/etc/my/file"
    pod.volumes("bar").asInstanceOf[PvcVolume].readOnly shouldBe true
  }

  it should "parse properly the volume-mounts" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      kubernetes.pods {
                  |        driver {
                  |          volumes {
                  |            foo {
                  |              secret {
                  |                name = mysecret
                  |              }
                  |            }
                  |          }
                  |          containers.container {
                  |            volume-mounts {
                  |              foo {
                  |                mount-path = "/etc/my/file"
                  |                read-only = true
                  |              }
                  |            }
                  |          }
                  |        }
                  |        executor {
                  |          volumes {
                  |            bar {
                  |              secret {
                  |                name = anothersecret
                  |              }
                  |            }
                  |          }
                  |          containers.container {
                  |            volume-mounts {
                  |              bar {
                  |                mount-path = "/etc/mc/fly"
                  |                read-only =  false
                  |              }
                  |            }
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}
                  |""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    val driver = res.get.cloudflow.streamlets("my-streamlet").kubernetes.pods("driver")
    val executor = res.get.cloudflow.streamlets("my-streamlet").kubernetes.pods("executor")
    val foo = driver.containers("container").volumeMounts("foo")
    val bar = executor.containers("container").volumeMounts("bar")

    foo.mountPath shouldBe "/etc/my/file"
    foo.readOnly shouldBe true
    bar.mountPath shouldBe "/etc/mc/fly"
    bar.readOnly shouldBe false
  }

  it should "fail if a volume-mount is not declared as volume" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      kubernetes.pods {
                  |        driver {
                  |          volumes {
                  |            foo {
                  |              secret {
                  |                name = mysecret
                  |              }
                  |            }
                  |          }
                  |          containers.container {
                  |            volume-mounts {
                  |              bar {
                  |                mount-path = "/etc/my/file"
                  |                read-only = true
                  |              }
                  |            }
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}
                  |""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains(InvalidMounts) shouldBe true
  }

  it should "not fail if a volume-mount is declared as volume in a generic section" in {
    // Arrange
    val config = """cloudflow {
                   |  streamlets {
                   |    my-streamlet {
                   |      kubernetes.pods {
                   |        pod {
                   |          volumes {
                   |            foo {
                   |              secret {
                   |                name = mysecret
                   |              }
                   |            }
                   |          }
                   |        }
                   |        driver {
                   |          containers.container {
                   |            volume-mounts {
                   |              foo {
                   |                mount-path = "/etc/my/file"
                   |                read-only = true
                   |              }
                   |            }
                   |          }
                   |        }
                   |      }
                   |    }
                   |  }
                   |}
                   |""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isSuccess shouldBe true
    val driver = res.get.cloudflow.streamlets("my-streamlet").kubernetes.pods("driver")
    val foo = driver.containers("container").volumeMounts("foo")
    foo.mountPath shouldBe "/etc/my/file"
    foo.readOnly shouldBe true
  }

  it should "fail if a volume-mount is declared as volume in another section" in {
    // Arrange
    val config = """cloudflow {
                   |  streamlets {
                   |    my-streamlet {
                   |      kubernetes.pods {
                   |        pod {
                   |          volumes {
                   |            foo {
                   |              secret {
                   |                name = mysecret1
                   |              }
                   |            }
                   |          }
                   |        }
                   |        driver {
                   |          volumes {
                   |            bar {
                   |              secret {
                   |                name = mysecret2
                   |              }
                   |            }
                   |          }
                   |          containers.container {
                   |            volume-mounts {
                   |              foo {
                   |                mount-path = "/etc/my/file1"
                   |                read-only = true
                   |              }
                   |              bar {
                   |                mount-path = "/etc/my/file2"
                   |                read-only = false
                   |              }
                   |            }
                   |          }
                   |        }
                   |      }
                   |    }
                   |  }
                   |}
                   |""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isSuccess shouldBe true
    val driver = res.get.cloudflow.streamlets("my-streamlet").kubernetes.pods("driver")
    val foo = driver.containers("container").volumeMounts("foo")
    val bar = driver.containers("container").volumeMounts("bar")
    foo.mountPath shouldBe "/etc/my/file1"
    foo.readOnly shouldBe true
    bar.mountPath shouldBe "/etc/my/file2"
    bar.readOnly shouldBe false
  }

  it should "validate labels and env variables" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      kubernetes.pods {
                  |        driver {
                  |          labels: {
                  |            key1 = value1
                  |            key2 = value2
                  |          }
                  |          annotations: {
                  |            akey1 = avalue1
                  |            akey2 = avalue2
                  |          }
                  |          containers.container {
                  |            env = [
                  |              {
                  |                name = "FOO"
                  |                value = "BAR"
                  |              }
                  |            ]
                  |          }
                  |        }
                  |        executor {
                  |          labels: {
                  |            key3 = value3
                  |            key4 = value4
                  |          }
                  |          annotations: {
                  |            akey3 = avalue3
                  |            akey4 = avalue4
                  |          }
                  |          containers.container {
                  |            env = [
                  |              {
                  |                name = "FFF"
                  |                value = "BBB"
                  |              }
                  |            ]
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isSuccess shouldBe true
    val driver = res.get.cloudflow.streamlets("my-streamlet").kubernetes.pods("driver")
    val executor = res.get.cloudflow.streamlets("my-streamlet").kubernetes.pods("executor")

    driver.labels(LabelKey("key1")) shouldBe LabelValue("value1")
    driver.labels(LabelKey("key2")) shouldBe LabelValue("value2")
    driver.annotations(AnnotationKey("akey1")) shouldBe AnnotationValue("avalue1")
    driver.annotations(AnnotationKey("akey2")) shouldBe AnnotationValue("avalue2")
    executor.labels(LabelKey("key3")) shouldBe LabelValue("value3")
    executor.labels(LabelKey("key4")) shouldBe LabelValue("value4")
    executor.annotations(AnnotationKey("akey3")) shouldBe AnnotationValue("avalue3")
    executor.annotations(AnnotationKey("akey4")) shouldBe AnnotationValue("avalue4")

    val driverContainerEnv = driver.containers("container").env.head
    val executorContainerEnv = executor.containers("container").env.head

    driverContainerEnv.name shouldBe "FOO"
    driverContainerEnv.value shouldBe "BAR"

    executorContainerEnv.name shouldBe "FFF"
    executorContainerEnv.value shouldBe "BBB"
  }

  it should "parse requests and limits quantities" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      kubernetes {
                  |        pods.pod.containers.container {
                  |          resources {
                  |            requests {
                  |              cpu = 1
                  |              memory = "512M"
                  |            }
                  |            limits {
                  |              cpu = 2
                  |              memory = 1024M
                  |            }
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isSuccess shouldBe true
    val resources = res.get.cloudflow
      .streamlets("my-streamlet")
      .kubernetes
      .pods("pod")
      .containers("container")
      .resources

    resources.requests.cpu.value.value shouldBe "1"
    resources.requests.memory.value.value shouldBe "512M"
    resources.limits.cpu.value.value shouldBe "2"
    resources.limits.memory.value.value shouldBe "1024M"
  }

  it should "fail parsing non-valid quantities" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      kubernetes {
                  |        pods.pod.containers.container {
                  |          resources {
                  |            requests {
                  |              cpu = 0.1Li
                  |            }
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("not a valid Kubernetes quantity") shouldBe true
  }

  it should "fail parsing non-valid bytes quantities" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      kubernetes {
                  |        pods.pod.containers.container {
                  |          resources {
                  |            requests {
                  |              memory = 1MiBu
                  |            }
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("not a valid Kubernetes quantity") shouldBe true
  }

  it should "parse quantities without format" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      kubernetes {
                  |        pods.pod.containers.container {
                  |          resources {
                  |            requests {
                  |              cpu = 0.1
                  |            }
                  |          }
                  |        }
                  |      }
                  |    }
                  |  }
                  |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "parse ports" in {
    // Arrange
    val config = """cloudflow {
                  |  streamlets {
                  |    my-streamlet {
                  |      kubernetes {
                  |        pods.pod.containers.container {
                  |          ports = [
                  |            { container-port=9001},
                  |            { container-port=9002, host-ip="my-ip", protocol = "SCTP", host-port=9999, name="sctp2"}
                  |          ]
                  |        }
                  |      }
                  |    }
                  |  }
                  |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isSuccess shouldBe true
    val ports = res.get.cloudflow
      .streamlets("my-streamlet")
      .kubernetes
      .pods("pod")
      .containers("container")
      .ports

    ports(0).containerPort shouldBe 9001
    ports(0).protocol shouldBe "TCP"
    ports(1).containerPort shouldBe 9002
    ports(1).hostIP shouldBe "my-ip"
    ports(1).protocol shouldBe "SCTP"
    ports(1).hostPort shouldBe Some(9999)
    ports(1).name shouldBe Some(ContainerPortName("sctp2"))
  }

  it should "fail on invalid port names" in {
    // Arrange
    val config = """cloudflow.streamlets.my-streamlet.kubernetes.pods.pod.containers.container.ports = [
                  |  { container-port=9001, name="-hello" }
                  |]""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("-hello") shouldBe true
  }

  it should "fail on too long port names" in {
    // Arrange
    val config = """cloudflow.streamlets.my-streamlet.kubernetes.pods.pod.containers.container.ports = [
                  |  { container-port=9001, name="hello-i-am-someone" }
                  |]""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("hello-i-am-someone") shouldBe true
  }

  it should "fail on port names with double --" in {
    // Arrange
    val config = """cloudflow.streamlets.my-streamlet.kubernetes.pods.pod.containers.container.ports = [
                  |  { container-port=9001, name="hello--1" }
                  |]""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("hello--1") shouldBe true
  }

  it should "fail on port names with invalid chars" in {
    // Arrange
    val config = """cloudflow.streamlets.my-streamlet.kubernetes.pods.pod.containers.container.ports = [
                  |  { container-port=9001, name="hello_" }
                  |]""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("hello_") shouldBe true
  }

  def getConfigWithLabel(str: String) = {
    s"""cloudflow {
     |  streamlets {
     |    my-streamlet {
     |      kubernetes.pods {
     |        pod {
     |          labels: {
     |            ${str}
     |          }
     |        }
     |      }
     |    }
     |  }
     |}""".stripMargin
  }

  def getConfigWithAnnotation(str: String) = {
    s"""cloudflow {
     |  streamlets {
     |    my-streamlet {
     |      kubernetes.pods {
     |        pod {
     |          annotations: {
     |            ${str}
     |          }
     |        }
     |      }
     |    }
     |  }
     |}""".stripMargin
  }

  it should "validate subdomain labels" in {
    // Arrange
    val config = getConfigWithLabel("subdomain123/KEY1 = VALUE1")

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isSuccess shouldBe true
    val pod = res.get.cloudflow.streamlets("my-streamlet").kubernetes.pods("pod")

    pod.labels(LabelKey("subdomain123/KEY1")) shouldBe LabelValue("VALUE1")
  }

  it should "fail to parse invalid subdomains labels" in {
    // Arrange
    val config = getConfigWithLabel("SUBDOMAIN123/KEY1 = VALUE1")

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("SUBDOMAIN123/KEY1") shouldBe true
  }

  it should "fail to parse invalid maformed labels" in {
    // Arrange
    val config = getConfigWithLabel("key1")

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("key1") shouldBe true
  }

  it should "fail to parse too long labels" in {
    // Arrange
    val config =
      getConfigWithLabel("keyabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz = value2")

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage
      .contains("keyabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz") shouldBe true
  }

  it should "fail to parse malformed keys" in {
    // Arrange
    val config = getConfigWithLabel(""""keyabcdefstuv+zabcdefghijklmnopqrstuvwxyz" = value2""")

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("keyabcdefstuv+zabcdefghijklmnopqrstuvwxyz") shouldBe true
  }

  it should "fail to parse more malformed keys" in {
    // Arrange
    val config = getConfigWithLabel(""""lkjsdfsdf..sdfsfd//keyabcdefstuvzabcdefghijklmnopqrstuvwxyz" = value2""")

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage
      .contains("lkjsdfsdf..sdfsfd//keyabcdefstuvzabcdefghijklmnopqrstuvwxyz") shouldBe true
  }

  it should "parse strange keys" in {
    // Arrange
    val config = getConfigWithLabel(""""lkjsdfsdfsdfsfd/keyabcdefstuvzabcdefghijklmnopqrstuvwxyz": value2""")

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isSuccess shouldBe true
    val pod = res.get.cloudflow.streamlets("my-streamlet").kubernetes.pods("pod")

    pod.labels(LabelKey("lkjsdfsdfsdfsfd/keyabcdefstuvzabcdefghijklmnopqrstuvwxyz")) shouldBe LabelValue("value2")
  }

  it should "fail to parse keys with tailing slash" in {
    // Arrange
    val config = getConfigWithLabel(""""lkjsdfsdfsdfsfd/keyabcdefstuvzabcdefghijklmnopqrstuvwxyz/": value2""")

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("lkjsdfsdfsdfsfd/keyabcdefstuvzabcdefghijklmnopqrstuvwxyz/") shouldBe true
  }

  // the annotations and labels use the same validation logic for keys, so only added these.
  it should "validate subdomain annotations" in {
    // Arrange
    val config = getConfigWithAnnotation("subdomain123/KEY1 = VALUE1")

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isSuccess shouldBe true
    val pod = res.get.cloudflow.streamlets("my-streamlet").kubernetes.pods("pod")

    pod.annotations(AnnotationKey("subdomain123/KEY1")) shouldBe AnnotationValue("VALUE1")
  }

  it should "fail to parse invalid subdomains annotations" in {
    // Arrange
    val config = getConfigWithAnnotation("SUBDOMAIN123/KEY1 = VALUE1")

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("SUBDOMAIN123/KEY1") shouldBe true
  }

  // more info in https://github.com/lyft/flinkk8soperator/blob/master/pkg/apis/app/v1beta1/types.go
  // metav1.ObjectMeta only exists in type `FlinkApplication` not in `TaskManagerConfig` nor `JobManagerConfig`
  it should "fail to parse job-manager pods with labels" in {
    // Arrange
    val config = s"""cloudflow {
                   |  streamlets {
                   |    flink {
                   |      kubernetes.pods {
                   |        job-manager {
                   |          labels: {
                   |            key = value
                   |          }
                   |        }
                   |      }
                   |    }
                   |  }
                   |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains(LabelsNotAllowedOnPod) shouldBe true
  }

  it should "write label values as plain strings" in {
    // Arrange
    val config = s"""cloudflow {
                   |  streamlets {
                   |    flink {
                   |      kubernetes.pods {
                   |        pod {
                   |          labels: {
                   |            mykey = myvalue
                   |          }
                   |        }
                   |      }
                   |    }
                   |  }
                   |}""".stripMargin

    // Act
    val res = ConfigFactory.empty().withFallback(writeConfig(loadAndValidate(ConfigSource.string(config)).get))

    // Assert
    res.getString("cloudflow.streamlets.flink.kubernetes.pods.pod.labels.mykey") shouldBe "myvalue"
  }

  it should "generate proper default mounts" in {
    // Arrange
    val crFile = new File("./cloudflow-cli/src/test/resources/swiss-knife.json")
    Serialization.jsonMapper().registerModule(DefaultScalaModule)
    val appCr = Serialization.jsonMapper().readValue(crFile, classOf[App.Cr])

    // Act
    val res = ConfigFactory.empty().withFallback(writeConfig(defaultMountsConfig(appCr.spec, List("flink", "spark"))))

    def getPvcPath(runtime: String) =
      s"cloudflow.runtimes.$runtime.kubernetes.pods.pod.volumes.default.pvc.name"
    def getVolumeMountPath(runtime: String) =
      s"cloudflow.runtimes.$runtime.kubernetes.pods.pod.containers.container.volume-mounts.default.mount-path"

    // Assert
    res.getString(getPvcPath("spark")) shouldBe "cloudflow-spark"
    res.getString(getVolumeMountPath("spark")) shouldBe "/mnt/spark/storage"
    res.getString(getPvcPath("flink")) shouldBe "cloudflow-flink"
    res.getString(getVolumeMountPath("flink")) shouldBe "/mnt/flink/storage"
  }

  it should "parse valid topic configuration and preserve the structure" in {
    // Arrange
    val config = s"""cloudflow {
                   |  topics {
                   |    my-topic {
                   |      producers = [streamlet-a1.out, streamlet-a2.out]
                   |      consumers = [streamlet-b.in]
                   |      cluster = test-server
                   |      connection-config = {
                   |        bootstrap.servers="my-server:9092"
                   |      }
                   |      producer-config = {
                   |        foo = bar
                   |      }
                   |      consumer-config = {
                   |        bar = baz
                   |      }
                   |      topic = {
                   |        partitions = 3
                   |        replicas = 5
                   |        extra-prop1 = 10
                   |        extra-prop2 = {
                   |          extra = bar
                   |        }
                   |      }
                   |      extra-key1 = foo
                   |      extra-key2 = {
                   |        extra-key3 = bar
                   |      }
                   |    }
                   |  }
                   |}""".stripMargin

    // Act
    val typesafeConfig = ConfigFactory.parseString(config)
    val res = loadAndValidate(typesafeConfig)
    lazy val configAfter = ConfigFactory.empty().withFallback(writeConfig(res.get))
    lazy val beforeKeys = typesafeConfig.root().entrySet().asScala.map(_.getKey)
    lazy val afterKeys = configAfter.root().entrySet().asScala.map(_.getKey)

    // Assert
    res.isSuccess shouldBe true
    beforeKeys should contain theSameElementsAs afterKeys
    val myTopic = res.get.cloudflow.topics("my-topic")
    myTopic.producers should contain theSameElementsAs List("streamlet-a1.out", "streamlet-a2.out")
    myTopic.consumers should contain theSameElementsAs List("streamlet-b.in")
    myTopic.cluster.value shouldBe "test-server"
    myTopic.connectionConfig.getString("bootstrap.servers") shouldBe "my-server:9092"
    myTopic.producerConfig.getString("foo") shouldBe "bar"
    myTopic.consumerConfig.getString("bar") shouldBe "baz"
    myTopic.topic.partitions.value shouldBe 3
    myTopic.topic.replicas.value shouldBe 5
    val myTopicConfig = configAfter.getConfig("cloudflow.topics.my-topic")
    myTopicConfig.getString("extra-key1") shouldBe "foo"
    myTopicConfig.getString("extra-key2.extra-key3") shouldBe "bar"
    myTopicConfig.getInt("topic.extra-prop1") shouldBe 10
    myTopicConfig.getString("topic.extra-prop2.extra") shouldBe "bar"
  }

  it should "fail to parse invalid topic configurations" in {
    // Arrange
    val config = s"""cloudflow {
                   |  topics {
                   |    my-topic {
                   |      producers = test
                   |    }
                   |  }
                   |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("cloudflow.topics.my-topic.producers") shouldBe true
  }

  it should "fail to parse invalid topicconfig configurations" in {
    // Arrange
    val config = s"""cloudflow {
                   |  topics {
                   |    my-topic {
                   |      topic { replicas = test }
                   |    }
                   |  }
                   |}""".stripMargin

    // Act
    val res = loadAndValidate(ConfigSource.string(config))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("cloudflow.topics.my-topic.topic.replicas") shouldBe true
  }
}
