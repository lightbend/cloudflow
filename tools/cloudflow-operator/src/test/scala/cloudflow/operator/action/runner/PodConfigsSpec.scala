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

package cloudflow.operator.action.runner

import akka.cloudflow.config.UnsafeCloudflowConfigLoader
import com.typesafe.config._
import org.scalatest._
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import io.fabric8.kubernetes.api.model.{ EnvVarBuilder, Quantity }

class PodsConfigSpec extends AnyWordSpecLike with OptionValues with TryValues with Matchers {

  "PodsConfig" should {
    "load from kubernetes config " in {
      val conf = """
      kubernetes {
        pods {
          pod {
            containers {
              container {
                env = [
                  { name = "JAVA_OPTS", value = "-XX:MaxRAMPercentage=40.0" }
                ]
                resources {
                  requests {
                    cpu = 500m         
                  }
                  limits {
                    cpu = 2
                  }
                }
              }
            }
          }
          driver {
            containers {
              container {
                env = [
                  { name = "MY_OPT_1", value = "1" },
                  { name = "MY_OPT_2", value = "2" }
                ]
              }
            }
          }
          executor {
            containers {
              container {
                resources {
                  requests {
                    memory = 200M
                  }
                  limits {
                    memory = 1200M
                  }
                }
              }
              custom-container {
                env = [
                  { name = "MY_OPT_3", value = "3" }
                ]
              }
            }
          }
        }
      }
      """
      val config = ConfigFactory.parseString(conf)
      val kubernetes = UnsafeCloudflowConfigLoader.loadPodConfig(config).success.value
      val podConfigs = PodsConfig.fromKubernetes(kubernetes).success.value
      podConfigs
        .pods("pod")
        .containers("container")
        .env(0) mustBe (new EnvVarBuilder().withName("JAVA_OPTS").withValue("-XX:MaxRAMPercentage=40.0").build())
      Quantity.getAmountInBytes(podConfigs.pods("pod").containers("container").resources.value.getRequests.get("cpu")) mustBe Quantity
        .getAmountInBytes(Quantity.parse("500m"))
      Quantity.getAmountInBytes(podConfigs.pods("pod").containers("container").resources.value.getLimits.get("cpu")) mustBe Quantity
        .getAmountInBytes(Quantity.parse("2"))
      podConfigs
        .pods("driver")
        .containers("container")
        .env(0) mustBe (new EnvVarBuilder().withName("MY_OPT_1").withValue("1").build())
      podConfigs
        .pods("driver")
        .containers("container")
        .env(1) mustBe (new EnvVarBuilder().withName("MY_OPT_2").withValue("2").build())
      podConfigs
        .pods("driver")
        .containers("container")
        .resources mustBe empty
      podConfigs
        .pods("executor")
        .containers("container")
        .env mustBe empty
      podConfigs
        .pods("executor")
        .containers("custom-container")
        .env must not be empty
      podConfigs
        .pods("executor")
        .containers("custom-container")
        .env(0) mustBe (new EnvVarBuilder().withName("MY_OPT_3").withValue("3").build())
    }

    "load from json config" in {
      val config = ConfigFactory.parseString(""" {
  "kubernetes": {
    "pods": {
      "pod": {
        "containers": {
          "container": {
            "env": [
              {
                "name": "JAVA_OPTS",
                "value": "-XX:MaxRAMPercentage=40.0"
              },
              {
                "name": "FOO",
                "value": "BAR"
              }
            ],
            "resources": {
              "limits": {
                "memory": "1024M"
              },
              "requests": {
                "memory": "2024M"
              }
            }
          }
        }
      }
    }
  }
} """)
      val kubernetes = UnsafeCloudflowConfigLoader.loadPodConfig(config).success.value
      val podConfigs = PodsConfig.fromKubernetes(kubernetes).success.value
      podConfigs
        .pods("pod")
        .containers("container")
        .env(0) mustBe (new EnvVarBuilder().withName("JAVA_OPTS").withValue("-XX:MaxRAMPercentage=40.0").build())
      // TODO: write Release notes that we don't support fallback to Memory size anymore
      // falling back to ConfigMemorySize, which allows specifying like MiB, MB, etc.
      Quantity.getAmountInBytes(
        podConfigs.pods("pod").containers("container").resources.value.getRequests.get("memory")) mustBe Quantity
        .getAmountInBytes(Quantity.parse("2024000000"))
    }
  }
}
