/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.{ Cli, CliException }
import akka.cli.cloudflow.kubeclient.KubeClient

import scala.util.{ Failure, Success, Try }

trait WithProtocolVersion {

  def validateProtocolVersion(client: KubeClient): Try[String] = {
    (for {
      version <- client.getOperatorProtocolVersion()
    } yield {
      version match {
        case v if v == Cli.ProtocolVersion =>
          Success(version)
        case ver =>
          val pVersion = Integer.parseInt(Cli.ProtocolVersion)
          Integer.parseInt(ver) match {
            case v if pVersion > v =>
              Failure(CliException(
                "This version of kubectl cloudflow is not compatible with the Cloudflow operator, please upgrade the Cloudflow operator"))
            case v if pVersion < v =>
              Failure(CliException(
                "This version of kubectl cloudflow is not compatible with the Cloudflow operator, please upgrade kubectl cloudflow"))
          }
      }
    }).flatten
  }

}
