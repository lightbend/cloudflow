/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.buildtool

import java.io.File

import com.typesafe.config.{ Config, ConfigFactory }

import scala.util.Try

case class LocalConfig(path: Option[String], content: Config, message: String) {
  def isPresent: Boolean = path.nonEmpty
}
object LocalConfig {
  val emptyConfig = ConfigFactory.empty()
  val configFound: String => String = file => "Using Sandbox local configuration file: " + file
  val noConfigProvided: String =
    """No configuration file provided for the local runner.
      |Set the localConfig property to point to the location of your local config file""".stripMargin
  val invalidConfig: String => String => String = file =>
    error => s"Invalid or corrupt configuration in file [$file]. Reason: [$error]"
  val configNotFound: String => String = file => s"""The provided configuration at [$file] does not exist."""

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
            parsedConfig => LocalConfig(Some(locFile.getAbsolutePath), parsedConfig, configFound(loc)))
        }
      }
      .getOrElse(LocalConfig(None, ConfigFactory.empty(), noConfigProvided))
}
