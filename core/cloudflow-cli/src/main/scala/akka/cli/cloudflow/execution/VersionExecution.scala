/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import scala.util.{ Success, Try }
import akka.cli.cloudflow.{ Execution, VersionResult }
import akka.cli.cloudflow.commands.Version
import buildinfo.BuildInfo

final case class VersionExecution(v: Version) extends Execution[VersionResult] {
  def run(): Try[VersionResult] = {
    Success(VersionResult(BuildInfo.version))
  }
}
