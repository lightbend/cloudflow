/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

object CliException {

  def apply(msg: String) = new CliException(msg)
  def apply(msg: String, cause: Throwable = null) = new CliException(msg, cause)

}

class CliException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)
