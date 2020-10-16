/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.operator.event

import cloudflow.blueprint.deployment.StreamletDeployment
import cloudflow.blueprint.deployment.Topic
import org.scalatest.{ ConfigMap => _ }
import org.scalatest._
import cloudflow.operator.TestDeploymentContext
import com.typesafe.config.ConfigFactory

class ConfigurationScopeLayeringSpec
    extends WordSpec
    with MustMatchers
    with GivenWhenThen
    with EitherValues
    with OptionValues
    with Inspectors
    with TestDeploymentContext {

  "ConfigurationScopeLayering" should {
    "transform the config" in {
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
        runtimes.akka.config {
          akka.loglevel = INFO
          akka.kafka.producer.parallelism = 15000
        }
      }
      cloudflow.streamlets.logger.config-parameters.log-level="info"
      cloudflow.streamlets.logger.config-parameters.msg-prefix="valid-logger"      
      """)

      val runtime       = "akka"
      val streamletName = "logger"
      val loggerConfig  = ConfigurationScopeLayering.mergeToStreamletConfig(runtime, streamletName, appConfig)

      loggerConfig.getString("cloudflow.streamlets.logger.log-level") mustBe "info"
      loggerConfig.getString("cloudflow.streamlets.logger.foo") mustBe "bar"
      loggerConfig.getString("cloudflow.streamlets.logger.msg-prefix") mustBe "valid-logger"
      loggerConfig.getInt("akka.kafka.producer.parallelism") mustBe 15000
      loggerConfig.getString("akka.loglevel") mustBe "DEBUG"
      loggerConfig.getMemorySize("kubernetes.pods.pod.containers.cloudflow.resources.requests.memory").toBytes mustBe 1024 * 1024 * 1024
    }

    "transform the Kafka config for a StreamletDeployment" in {
      val deployment = StreamletDeployment(
        name = "some-app-id",
        runtime = "akka",
        image = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629",
        streamletName = "akka-streamlet",
        className = "cloudflow.operator.runner.AkkaRunner",
        endpoint = None,
        secretName = "akka-streamlet",
        config = ConfigFactory.empty(),
        portMappings = Map(
          "maybe"     -> Topic("maybe-valid"),
          "sometimes" -> Topic("sometimes"),
          "invalid"   -> Topic("invalid-metrics"),
          "valid"     -> Topic("valid-metrics", cluster = Some("non-default-named-cluster")),
          "in" -> Topic(
                "metrics",
                config = ConfigFactory.parseString("""
                                                 |bootstrap.servers = "inline-config-kafka-bootstrap:9092"
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
              )
        ),
        volumeMounts = None,
        replicas = None
      )

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

      val clusterSecretConfigs = Map("default" -> defaultClusterConfig, "non-default-named-cluster" -> nonDefaultClusterConfig)

      val configs = ConfigurationScopeLayering.configs(deployment, appConfig, clusterSecretConfigs)

      // 'maybe' port uses 'default' named config
      val maybeValidPort   = "maybe"
      val maybeValidConfig = configs.streamlet.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$maybeValidPort.config")
      maybeValidConfig.getString("bootstrap.servers") mustBe "default-named-cluster:9092"
      maybeValidConfig.getConfig("connection-config").getString("connection.foo.bar") mustBe "default-baz"
      maybeValidConfig.getConfig("producer-config").getString("producer.foo.bar") mustBe "default-baz"
      maybeValidConfig.getConfig("consumer-config").getString("consumer.foo.bar") mustBe "default-baz"

      // 'sometimes' port uses global topic configuration from app config, and falls back 'default' named config for bootstrap.servers and other config
      val sometimesPort   = "sometimes"
      val sometimesConfig = configs.streamlet.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$sometimesPort.config")
      sometimesConfig.getString("bootstrap.servers") mustBe "default-named-cluster:9092"
      sometimesConfig.getConfig("connection-config").getString("connection.foo.bar") mustBe "default-baz"
      sometimesConfig.getConfig("connection-config").getString("connection2.foo.bar") mustBe "sometimes-baz"
      sometimesConfig.getConfig("producer-config").getString("producer.foo.bar") mustBe "sometimes-baz"
      sometimesConfig.getConfig("consumer-config").getString("consumer.foo.bar") mustBe "sometimes-baz"

      // 'invalid' port uses global topic configuration from app config
      val invalidPort   = "invalid"
      val invalidConfig = configs.streamlet.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$invalidPort.config")
      invalidConfig.getString("bootstrap.servers") mustBe "app-cluster:9092"
      invalidConfig.getConfig("connection-config").getString("connection.foo.bar") mustBe "app-baz"
      invalidConfig.getConfig("producer-config").getString("producer.foo.bar") mustBe "app-baz"
      invalidConfig.getConfig("consumer-config").getString("consumer.foo.bar") mustBe "app-baz"

      // 'valid' port uses a user-defined 'non-default' named config
      val validPort   = "valid"
      val validConfig = configs.streamlet.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$validPort.config")
      validConfig.getString("bootstrap.servers") mustBe "non-default-named-cluster:9092"
      validConfig.getConfig("connection-config").getString("connection.foo.bar") mustBe "non-default-baz"
      validConfig.getConfig("producer-config").getString("producer.foo.bar") mustBe "non-default-baz"
      validConfig.getConfig("consumer-config").getString("consumer.foo.bar") mustBe "non-default-baz"

      // 'in' port uses inline config
      val inPort   = "in"
      val inConfig = configs.streamlet.getConfig(s"cloudflow.runner.streamlet.context.port_mappings.$inPort.config")
      inConfig.getString("bootstrap.servers") mustBe "inline-config-kafka-bootstrap:9092"
      inConfig.getConfig("connection-config").getString("connection.foo.bar") mustBe "inline-baz"
      inConfig.getConfig("producer-config").getString("producer.foo.bar") mustBe "inline-baz"
      inConfig.getConfig("consumer-config").getString("consumer.foo.bar") mustBe "inline-baz"
    }
  }
}
