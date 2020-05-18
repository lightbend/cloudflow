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

import org.scalatest.{ ConfigMap ⇒ _, _ }
import cloudflow.operator.TestDeploymentContext
import com.typesafe.config.ConfigFactory

class ConfigInputChangeEventSpec
    extends WordSpec
    with MustMatchers
    with GivenWhenThen
    with EitherValues
    with OptionValues
    with Inspectors
    with TestDeploymentContext {

  "ConfigInputChangeEvent" should {
    "transform the config" in {
      val appConfig = ConfigFactory.parseString("""
      cloudflow {
        streamlets.logger {
          config-parameters {
            log-level = warning
          }
          config {
            akka.loglevel = "DEBUG"
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
      import ConfigInputChangeEvent._

      val streamletName = "logger"
      val runtimeConfig = getStreamletRuntimeConfig("akka", streamletName, appConfig)

      val streamletConfig = getStreamletConfig(streamletName, appConfig, runtimeConfig)
      var loggerConfig    = moveConfigParameters(streamletConfig, streamletName)
      loggerConfig = mergeRuntimeConfigToRoot(loggerConfig, streamletName)
      loggerConfig = removeKubernetesConfigSection(loggerConfig, streamletName)
      loggerConfig.getString("cloudflow.streamlets.logger.log-level") mustBe "info"
      loggerConfig.getString("cloudflow.streamlets.logger.msg-prefix") mustBe "valid-logger"
      loggerConfig.getInt("akka.kafka.producer.parallelism") mustBe 15000
      loggerConfig.getString("akka.loglevel") mustBe "DEBUG"
    }
  }
}
