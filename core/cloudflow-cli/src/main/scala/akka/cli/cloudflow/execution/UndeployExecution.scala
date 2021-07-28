/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.commands.Undeploy
import akka.cli.cloudflow.kubeclient.KubeClient
import akka.cli.cloudflow.{ CliLogger, Execution, UndeployResult }

import scala.util.Try

final case class UndeployExecution(u: Undeploy, client: KubeClient, logger: CliLogger)
    extends Execution[UndeployResult]
    with WithProtocolVersion {
  def run(): Try[UndeployResult] = {
    logger.info("Executing command Undeploy")
    for {
      _ <- validateProtocolVersion(client)
      _ <- client.deleteCloudflowApp(u.cloudflowApp, u.namespace.getOrElse(u.cloudflowApp))
    } yield {
      UndeployResult()
    }
  }
}
