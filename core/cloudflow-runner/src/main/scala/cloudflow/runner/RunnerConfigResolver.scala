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

package cloudflow.runner

import java.nio.file._
import com.typesafe.config._
import scala.util.Try

trait RunnerConfigResolver {

  final val ConfigMountPath       = "/etc/cloudflow-runner"
  final val ConfigFile            = "application.conf"
  final val ConfigSecretMountPath = "/etc/cloudflow-runner-secret"
  final val SecretConfigFile      = "secret.conf"

  def backwardsCompatConfig(configPath: Path): Path =
    if (Files.exists(configPath)) {
      configPath
    } else {
      // Backward compatibility: Use the ConfigMap populated by the operator
      Paths.get(s"$ConfigMountPath/$ConfigFile")
    }

  def makeConfig: Try[Config] = Try {
    val configFilePathString = Option(System.getProperty("config.file")).getOrElse(s"$ConfigSecretMountPath/$ConfigFile")
    val configPath           = Paths.get(configFilePathString)
    val secretPath           = Paths.get(s"$ConfigSecretMountPath/$SecretConfigFile")

    val applicationConfig = backwardsCompatConfig(configPath)

    val config = if (Files.exists(secretPath)) {
      println(s"Loading application.conf from: $applicationConfig, secret config from: $secretPath")
      // secret takes precedence, since it contains config.
      loadConfig(secretPath)
        .withFallback(loadConfig(applicationConfig))
        .withFallback(ConfigFactory.load)
    } else {
      println(s"Loading application.conf from: $applicationConfig")

      loadConfig(applicationConfig)
        .withFallback(ConfigFactory.load)
    }

    config
  }

  private def loadConfig(configPath: Path): Config =
    ConfigFactory.parseFile(configPath.toFile)
}
