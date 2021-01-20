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

package cloudflow.sbt

import java.io.File

import com.typesafe.config.{ Config, ConfigFactory }

import scala.util.Try

case class LocalConfig(path: Option[String], content: Config, message: String) {
  def isPresent: Boolean = path.nonEmpty
}
object LocalConfig {
  val emptyConfig                               = ConfigFactory.empty()
  val configFound: String => String             = file => "Using Sandbox local configuration file: " + file
  val noConfigProvided: String                  = """No configuration file provided for the local runner.
                                         |Set `runLocalConfigFile` in your build.sbt to point to the location of your local config file""".stripMargin
  val invalidConfig: String => String => String = file => error => s"Invalid or corrupt configuration in file [$file]. Reason: [$error]"
  val configNotFound: String => String          = file => s"""The provided configuration at [$file] does not exist.
                                                             | Check the value of `runLocalConfigFile` in your build.sbt """.stripMargin

  def load(location: Option[String]): LocalConfig =
    location
      .map { loc =>
        val locFile = new File(loc)
        if (!locFile.exists()) {
          LocalConfig(None, ConfigFactory.empty(), configNotFound(loc))
        } else {
          Try {
            ConfigFactory.parseFile(locFile)
          }.fold(
            throwable => LocalConfig(None, emptyConfig, invalidConfig(loc)(throwable.getMessage)),
            parsedConfig => LocalConfig(Some(locFile.getAbsolutePath), parsedConfig, configFound(loc))
          )
        }
      }
      .getOrElse(LocalConfig(None, ConfigFactory.empty(), noConfigProvided))
}
