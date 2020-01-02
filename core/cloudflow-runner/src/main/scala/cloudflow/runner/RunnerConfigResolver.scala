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

package cloudflow.runner

import java.nio.file._
import com.typesafe.config._
import scala.util.Try

trait RunnerConfigResolver {

  final val ConfigMountPath = "/etc/cloudflow-runner"
  final val ConfigFile = "application.conf"
  final val ConfigSecretMountPath = "/etc/cloudflow-runner-secret"
  final val SecretConfigFile = "secret.conf"

  def makeConfig: Try[Config] = Try {
    val configFilePathString = Option(System.getProperty("config.file")).getOrElse(s"$ConfigMountPath/$ConfigFile")
    val configPath = Paths.get(configFilePathString)
    val secretPath = Paths.get(s"$ConfigSecretMountPath/$SecretConfigFile")

    val config = if (Files.exists(secretPath)) {
      println(s"Loading application.conf from: $configPath, secret config from: $secretPath")

      loadConfig(configPath)
        .withFallback(loadConfig(secretPath))
        .withFallback(ConfigFactory.load)
    } else {
      println(s"Loading application.conf from: $configPath")

      loadConfig(configPath)
        .withFallback(ConfigFactory.load)
    }

    config
  }

  private def loadConfig(configPath: Path): Config = {
    ConfigFactory.parseFile(configPath.toFile)
  }
}
