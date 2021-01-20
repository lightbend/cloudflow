/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.{ CliLogger, Setup }
import akka.datap.crd.App
import com.typesafe.config.{ ConfigFactory, ConfigRenderOptions }
import io.fabric8.kubernetes.client.utils.Serialization
import org.scalatest.{ BeforeAndAfterAll, OptionValues, TryValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CloudflowStreamletConfigSpec
    extends AnyFlatSpec
    with Matchers
    with OptionValues
    with TryValues
    with BeforeAndAfterAll {

  override def beforeAll() = {
    Setup.init()
  }

  "The CloudflowConfig" should "return the correct streamlet configurations" in {
    // Arrange
    val appConfig = ConfigFactory.parseString("""
      cloudflow {
        streamlets.logger {
          config-parameters {
            log-level = warning
            foo = bar
          }
          config {
            akka.loglevel = "DEBUG"
          }
          kubernetes {
            pods {
              pod {
                containers {
                  cloudflow {
                    env = [
                      { name = "JAVA_OPTS"
                        value = "-XX:MaxRAMPercentage=40.0"
                      }
                    ]
                    # limited settings that we want to support
                    resources {
                      requests {
                        memory = "1G"
                      }
                    }
                  }
                }
              }
            }
          }
        }
        runtimes.akka {
          config {
            akka.loglevel = INFO
            akka.kafka.producer.parallelism = 15000
          }
          kubernetes {
            pods {
              pod {
                containers {
                  cloudflow {
                    resources {
                      limits {
                        memory = "3G"
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
      cloudflow.streamlets.logger.config-parameters.log-level="info"
      cloudflow.streamlets.logger.config-parameters.msg-prefix="valid-logger"
      """)

    // Act
    val cloudflowConfig = CloudflowConfig.loadAndValidate(appConfig).get

    val runtimeConfig = CloudflowConfig.runtimeConfig("logger", "akka", cloudflowConfig)

    val podConfig = CloudflowConfig.podsConfig("logger", "akka", cloudflowConfig)

    val completeConfig =
      CloudflowConfig.streamletConfig(streamletName = "logger", runtimeName = "akka", cloudflowConfig)

    // Assert
    runtimeConfig.getLong("akka.kafka.producer.parallelism") shouldBe 15000
    runtimeConfig.getString("akka.loglevel") shouldBe "DEBUG"

    podConfig
      .getConfigList("kubernetes.pods.pod.containers.cloudflow.env")
      .get(0)
      .getString("name") shouldBe "JAVA_OPTS"
    podConfig
      .getConfigList("kubernetes.pods.pod.containers.cloudflow.env")
      .get(0)
      .getString("value") shouldBe "-XX:MaxRAMPercentage=40.0"
    podConfig
      .getString("kubernetes.pods.pod.containers.cloudflow.resources.requests.memory") shouldBe "1G"
    podConfig
      .getString("kubernetes.pods.pod.containers.cloudflow.resources.limits.memory") shouldBe "3G"

    completeConfig.getString("cloudflow.streamlets.logger.log-level") shouldBe "info"
    completeConfig.getString("cloudflow.streamlets.logger.foo") shouldBe "bar"
    completeConfig.getString("cloudflow.streamlets.logger.msg-prefix") shouldBe "valid-logger"
    completeConfig.getInt("akka.kafka.producer.parallelism") shouldBe 15000
    completeConfig.getString("akka.loglevel") shouldBe "DEBUG"
    completeConfig
      .getMemorySize("kubernetes.pods.pod.containers.cloudflow.resources.requests.memory")
      .toBytes shouldBe 1024 * 1024 * 1024
  }

  it should "produce the same updated configuration for swiss-knife spark" in {
    // Arrange
    val config = ConfigFactory.parseString(
      """cloudflow.streamlets.spark-process.config-parameters.configurable-message="updated_config"""")

    val deployment = App.Deployment(
      name = "some-app-id",
      runtime = "spark",
      image = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629",
      streamletName = "spark-process",
      className = "cloudflow.operator.runner.SparkRunner",
      secretName = "spark-process",
      config = Serialization.jsonMapper().readTree("{}"),
      portMappings = Map(),
      volumeMounts = Seq(),
      endpoint = None,
      replicas = None)

    val clusterSecretConfigs =
      Map("default" -> ConfigFactory.parseString("""bootstrap.servers = "localhost:9092""""))

    // Act
    val cloudflowConfig = CloudflowConfig.loadAndValidate(config).get

    val completeConfig =
      CloudflowConfig.streamletConfig(streamletName = "spark-process", runtimeName = "spark", cloudflowConfig)

    val streamletConfig = {
      val sConfig =
        CloudflowConfig.streamletConfig(streamletName = "spark-process", runtimeName = "spark", cloudflowConfig)
      val withConfig = new WithConfiguration { val logger: CliLogger = new CliLogger(None) }

      withConfig.portMappings(
        deployment = deployment,
        appConfig = cloudflowConfig,
        streamletConfig = sConfig,
        clusterSecretConfigs)
    }

    // Assert
    completeConfig.getString("cloudflow.streamlets.spark-process.configurable-message") shouldBe "updated_config"
    streamletConfig.getString("cloudflow.streamlets.spark-process.configurable-message") shouldBe "updated_config"
  }

  it should "transform the Kafka config for a StreamletDeployment" in {
    // Arrange
    val emptyConfig = Serialization.jsonMapper().readTree("{}")
    val inlineConfig = Serialization
      .jsonMapper()
      .readTree(
        ConfigFactory
          .parseString("""bootstrap.servers = "inline-config-kafka-bootstrap:9092"
                         |connection-config {
                         |  connection.foo.bar = "inline-baz"
                         |}
                         |producer-config {
                         |  producer.foo.bar = "inline-baz"
                         |}
                         |consumer-config {
                         |  consumer.foo.bar = "inline-baz"
                         |}
                         |""".stripMargin)
          .root()
          .render(ConfigRenderOptions.concise()))

    val deployment = App.Deployment(
      name = "some-app-id",
      runtime = "akka",
      image = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629",
      streamletName = "akka-streamlet",
      className = "cloudflow.operator.runner.AkkaRunner",
      secretName = "akka-streamlet",
      config = emptyConfig,
      portMappings = Map(
        "maybe" -> App.PortMapping(id = "maybe-valid", config = null, cluster = None),
        "sometimes" -> App.PortMapping(id = "sometimes", config = null, cluster = None),
        "invalid" -> App.PortMapping(id = "invalid-metrics", config = null, cluster = None),
        "valid" -> App.PortMapping(id = "valid-metrics", config = null, cluster = Some("non-default-named-cluster")),
        "in" -> App.PortMapping(id = "metrics", config = inlineConfig, cluster = None)),
      volumeMounts = Seq(),
      endpoint = None,
      replicas = None)

    val appConfig = ConfigFactory.parseString("""
                                                |cloudflow.topics {
                                                |  invalid-metrics {
                                                |    bootstrap.servers = "app-cluster:9092"
                                                |    connection-config {
                                                |      connection.foo.bar = "app-baz"
                                                |    }
                                                |    producer-config {
                                                |      producer.foo.bar = "app-baz"
                                                |    }
                                                |    consumer-config {
                                                |      consumer.foo.bar = "app-baz"
                                                |    }
                                                |  }
                                                |  sometimes {
                                                |    connection-config {
                                                |      connection2.foo.bar = "sometimes-baz"
                                                |    }
                                                |    producer-config {
                                                |      producer.foo.bar = "sometimes-baz"
                                                |    }
                                                |    consumer-config {
                                                |      consumer.foo.bar = "sometimes-baz"
                                                |    }
                                                |  }
                                                |}
                                                |""".stripMargin)

    val defaultClusterConfig = ConfigFactory.parseString("""
                                                           |bootstrap.servers = "default-named-cluster:9092"
                                                           |connection-config {
                                                           |  connection.foo.bar = "default-baz"
                                                           |}
                                                           |producer-config {
                                                           |  producer.foo.bar = "default-baz"
                                                           |}
                                                           |consumer-config {
                                                           |  consumer.foo.bar = "default-baz"
                                                           |}
      """.stripMargin)

    val nonDefaultClusterConfig = ConfigFactory.parseString("""
                                                              |bootstrap.servers = "non-default-named-cluster:9092"
                                                              |connection-config {
                                                              |  connection.foo.bar = "non-default-baz"
                                                              |}
                                                              |producer-config {
                                                              |  producer.foo.bar = "non-default-baz"
                                                              |}
                                                              |consumer-config {
                                                              |  consumer.foo.bar = "non-default-baz"
                                                              |}
      """.stripMargin)

    val clusterSecretConfigs =
      Map("default" -> defaultClusterConfig, "non-default-named-cluster" -> nonDefaultClusterConfig)

    // Act
    val cloudflowConfig = CloudflowConfig.loadAndValidate(appConfig).get

    val streamletConfig = {
      val sConfig = CloudflowConfig.streamletConfig(streamletName = "logger", runtimeName = "akka", cloudflowConfig)
      val withConfig = new WithConfiguration { val logger: CliLogger = new CliLogger(None) }

      withConfig.portMappings(
        deployment = deployment,
        appConfig = cloudflowConfig,
        streamletConfig = sConfig,
        clusterSecretConfigs)
    }

    // Assert
    // 'maybe' port uses 'default' named config
    val maybeValidPort = "maybe"
    val maybeValidConfig =
      streamletConfig.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$maybeValidPort.config")
    maybeValidConfig.getString("bootstrap.servers") shouldBe "default-named-cluster:9092"
    maybeValidConfig.getConfig("connection-config").getString("connection.foo.bar") shouldBe "default-baz"
    maybeValidConfig.getConfig("producer-config").getString("producer.foo.bar") shouldBe "default-baz"
    maybeValidConfig.getConfig("consumer-config").getString("consumer.foo.bar") shouldBe "default-baz"
    streamletConfig.getString("cloudflow.kafka.bootstrap-servers") shouldBe "default-named-cluster:9092"
    // 'sometimes' port uses global topic configuration from app config, and falls back 'default' named config for bootstrap.servers and other config
    val sometimesPort = "sometimes"
    val sometimesConfig =
      streamletConfig.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$sometimesPort.config")
    sometimesConfig.getString("bootstrap.servers") shouldBe "default-named-cluster:9092"
    sometimesConfig.getConfig("connection-config").getString("connection.foo.bar") shouldBe "default-baz"
    sometimesConfig.getConfig("connection-config").getString("connection2.foo.bar") shouldBe "sometimes-baz"
    sometimesConfig.getConfig("producer-config").getString("producer.foo.bar") shouldBe "sometimes-baz"
    sometimesConfig.getConfig("consumer-config").getString("consumer.foo.bar") shouldBe "sometimes-baz"

    // 'invalid' port uses global topic configuration from app config
    val invalidPort = "invalid"
    val invalidConfig =
      streamletConfig.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$invalidPort.config")
    invalidConfig.getString("bootstrap.servers") shouldBe "app-cluster:9092"
    invalidConfig.getConfig("connection-config").getString("connection.foo.bar") shouldBe "app-baz"
    invalidConfig.getConfig("producer-config").getString("producer.foo.bar") shouldBe "app-baz"
    invalidConfig.getConfig("consumer-config").getString("consumer.foo.bar") shouldBe "app-baz"

    // 'valid' port uses a user-defined 'non-default' named config
    val validPort = "valid"
    val validConfig = streamletConfig.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$validPort.config")
    validConfig.getString("bootstrap.servers") shouldBe "non-default-named-cluster:9092"
    validConfig.getConfig("connection-config").getString("connection.foo.bar") shouldBe "non-default-baz"
    validConfig.getConfig("producer-config").getString("producer.foo.bar") shouldBe "non-default-baz"
    validConfig.getConfig("consumer-config").getString("consumer.foo.bar") shouldBe "non-default-baz"

    // 'in' port uses inline config
    val inPort = "in"
    val inConfig = streamletConfig.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$inPort.config")
    inConfig.getString("bootstrap.servers") shouldBe "inline-config-kafka-bootstrap:9092"
    inConfig.getConfig("connection-config").getString("connection.foo.bar") shouldBe "inline-baz"
    inConfig.getConfig("producer-config").getString("producer.foo.bar") shouldBe "inline-baz"
    inConfig.getConfig("consumer-config").getString("consumer.foo.bar") shouldBe "inline-baz"
  }

  it should "override the Kafka config for a StreamletDeployment from configuration" in {
    // Arrange
    val emptyConfig = Serialization.jsonMapper().readTree("{}")

    val deployment = App.Deployment(
      name = "some-app-id",
      runtime = "akka",
      image = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629",
      streamletName = "akka-streamlet",
      className = "cloudflow.operator.runner.AkkaRunner",
      secretName = "akka-streamlet",
      config = emptyConfig,
      portMappings = Map(
        "valid" -> App.PortMapping(id = "valid-metrics", config = null, cluster = Some("deployment-named-cluster"))),
      volumeMounts = Seq(),
      endpoint = None,
      replicas = None)

    val appConfig = ConfigFactory.parseString("""
                                                |cloudflow.topics {
                                                |  valid {
                                                |    cluster = from-config-named-cluster
                                                |  }
                                                |}
                                                |""".stripMargin)

    val defaultClusterConfig =
      ConfigFactory.parseString("""bootstrap.servers = "default-named-cluster:9092"""".stripMargin)

    val deploymentClusterConfig =
      ConfigFactory.parseString("""
                                  |bootstrap.servers = "deployment-named-cluster:9092"
                                  |connection-config {
                                  |  connection.foo.bar = "deployment-baz"
                                  |}
                                  |producer-config {
                                  |  producer.foo.bar = "deployment-baz"
                                  |}
                                  |consumer-config {
                                  |  consumer.foo.bar = "deployment-baz"
                                  |}""".stripMargin)

    val fromConfigClusterConfig =
      ConfigFactory.parseString("""
                                  |bootstrap.servers = "from-config-named-cluster:9092"
                                  |connection-config {
                                  |  connection.foo.bar = "from-config-baz"
                                  |}
                                  |producer-config {
                                  |  producer.foo.bar = "from-config-baz"
                                  |}
                                  |consumer-config {
                                  |  consumer.foo.bar = "from-config-baz"
                                  |}""".stripMargin)

    val clusterSecretConfigs =
      Map(
        "default" -> defaultClusterConfig,
        "deployment-named-cluster" -> deploymentClusterConfig,
        "from-config-named-cluster" -> fromConfigClusterConfig)

    // Act
    val cloudflowConfig = CloudflowConfig.loadAndValidate(appConfig).get

    val streamletConfig = {
      val sConfig = CloudflowConfig.streamletConfig(streamletName = "logger", runtimeName = "akka", cloudflowConfig)
      val withConfig = new WithConfiguration { val logger: CliLogger = new CliLogger(None) }

      withConfig.portMappings(
        deployment = deployment,
        appConfig = cloudflowConfig,
        streamletConfig = sConfig,
        clusterSecretConfigs)
    }

    // Assert
    // 'valid' port uses a user-defined 'non-default' named config
    val validPort = "valid"
    val validConfig = streamletConfig.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$validPort.config")
    validConfig.getString("bootstrap.servers") shouldBe "from-config-named-cluster:9092"
    validConfig.getConfig("connection-config").getString("connection.foo.bar") shouldBe "from-config-baz"
    validConfig.getConfig("producer-config").getString("producer.foo.bar") shouldBe "from-config-baz"
    validConfig.getConfig("consumer-config").getString("consumer.foo.bar") shouldBe "from-config-baz"
  }

}
