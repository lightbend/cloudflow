/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cloudflow.config

object ConfigException {

  def apply(msg: String) = new ConfigException(msg)
  def apply(msg: String, cause: Throwable = null) = new ConfigException(msg, cause)

}

class ConfigException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)
