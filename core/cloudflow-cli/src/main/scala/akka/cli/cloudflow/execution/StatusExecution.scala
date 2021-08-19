/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import scala.util.Try

import akka.cli.cloudflow.{ CliLogger, Execution, StatusResult }
import akka.cli.cloudflow.commands.Status
import akka.cli.cloudflow.kubeclient.KubeClient

final case class StatusExecution(s: Status, client: KubeClient, logger: CliLogger)
    extends Execution[StatusResult]
    with WithProtocolVersion {
  def run(): Try[StatusResult] = {
    logger.info("Executing command Status")
    for {
      _ <- validateProtocolVersion(client)
      res <- client.getCloudflowAppStatus(s.cloudflowApp, s.namespace.getOrElse(s.cloudflowApp))
    } yield {
      StatusResult(res)
    }
  }
}
