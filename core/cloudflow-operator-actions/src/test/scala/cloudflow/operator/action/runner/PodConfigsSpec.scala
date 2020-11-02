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

package cloudflow.operator.action.runner

import com.typesafe.config._
import org.scalatest._
import skuber._
import skuber.Resource._

class PodsConfigSpec extends WordSpecLike with OptionValues with TryValues with MustMatchers {

  "PodsConfig" should {
    "load from kubernetes config " in {
      val conf       = """
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
      val config     = ConfigFactory.parseString(conf)
      val podConfigs = PodsConfig.fromConfig(config).success.value
      podConfigs.pods("pod").containers("container").env(0) mustBe (EnvVar("JAVA_OPTS", EnvVar.StringValue("-XX:MaxRAMPercentage=40.0")))
      podConfigs.pods("pod").containers("container").resources.value.requests("cpu") mustBe Quantity("500m")
      podConfigs.pods("pod").containers("container").resources.value.limits("cpu") mustBe Quantity("2")
      podConfigs.pods("driver").containers("container").env(0) mustBe (EnvVar("MY_OPT_1", EnvVar.StringValue("1")))
      podConfigs.pods("driver").containers("container").env(1) mustBe (EnvVar("MY_OPT_2", EnvVar.StringValue("2")))
      podConfigs.pods("driver").containers("container").resources mustBe empty
      podConfigs.pods("executor").containers("container").env mustBe empty
      podConfigs.pods("executor").containers("custom-container").env must not be empty
      podConfigs.pods("executor").containers("custom-container").env(0) mustBe (EnvVar("MY_OPT_3", EnvVar.StringValue("3")))
    }

    "load from json config" in {
      val config     = ConfigFactory.parseString(""" {
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
                "memory": "1024MB"
              },
              "requests": {
                "memory": "2024MiB"
              }
            }
          }
        }
      }
    }
  }
} """)
      val podConfigs = PodsConfig.fromConfig(config).success.value
      podConfigs.pods("pod").containers("container").env(0) mustBe (EnvVar("JAVA_OPTS", EnvVar.StringValue("-XX:MaxRAMPercentage=40.0")))
      // falling back to ConfigMemorySize, which allows specifying like MiB, MB, etc.
      podConfigs.pods("pod").containers("container").resources.value.requests("memory") mustBe Quantity("2122317824")
    }
  }
}
