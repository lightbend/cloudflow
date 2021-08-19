/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.flink

import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroOutlet
import cloudflow.flink.avro.Data

import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment

import com.typesafe.config.{ Config, ConfigFactory }

import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec._
import org.scalatest.matchers.should._

class FlinkStreamletConfigSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  "FlinkStreamlet" should {

    object FlinkIngress extends FlinkStreamlet {
      val out = AvroOutlet[Data]("out", _.id.toString())
      val shape = StreamletShape(out)

      override def createLogic() = new FlinkStreamletLogic {
        override def buildExecutionGraph = ???

      }

      def accessStreamExecutionEnvironment(config: Config, streamlet: String): StreamExecutionEnvironment =
        FlinkIngress.createStreamExecutionEnvironment(config, streamlet)

    }

    "find config has enabled checkpointing by default" in {
      val config = ConfigFactory.parseString("")

      val env = FlinkIngress.accessStreamExecutionEnvironment(config, "fake")
      env.getCheckpointConfig.isCheckpointingEnabled() shouldBe true
    }

    "find checkpointing is disabled by runtime" in {
      val config = ConfigFactory.parseString("cloudflow.runtimes.flink.config.cloudflow.checkpointing.default = off")
      FlinkIngress.isDefaultCheckpointingEnabled(config, "fake") shouldBe false
    }

    "find checkpointing is disabled by streamlet" in {
      val config =
        ConfigFactory.parseString("cloudflow.streamlet.my-streamlet.config.cloudflow.checkpointing.default = off")
      FlinkIngress.isDefaultCheckpointingEnabled(config, "my-streamlet") shouldBe false
    }

    "find checkpointing is enabled when nor runtime nor stream has that param" in {
      val config = ConfigFactory.parseString("cloudflow.streamlet.my-streamlet.kuberneter.bla.bla = yadayada")
      FlinkIngress.isDefaultCheckpointingEnabled(config, "my-streamlet") shouldBe true
    }

    "find checkpointing is disabled for streamlet overriding runtimes being enabled" in {
      val config =
        ConfigFactory.parseString("""
        cloudflow.runtimes.flink.config.cloudflow.checkpointing.default = on
        cloudflow.streamlet.my-streamlet.config.cloudflow.checkpointing.default = off
        """)
      FlinkIngress.isDefaultCheckpointingEnabled(config, "my-streamlet") shouldBe false
    }

    "find checkpointing is configured according to config" in {

      val config = """{
                        "akka": {
                           "actor": {
                             "additional-serialization-bindings": {
                               "akka.actor.ActorInitializationException": "akka-misc"
                             }
                           }
                        },
                        "cloudflow": {
                          "runtimes": {
                            "flink": {
                              "config": {
                                "cloudflow": {
                                  "checkpointing": {
                                    "default": false
                                  }
                                }
                              }
                            }
                          }
                        },
                        "file": {
                          "separator": "/"
                        }
                      }""".stripMargin
      FlinkIngress.isDefaultCheckpointingEnabled(ConfigFactory.parseString(config), "fake") shouldBe false
    }
  }
}
