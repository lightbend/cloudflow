/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import scala.util.Try

import akka.cli.cloudflow.{ CliLogger, Execution, ListResult }
import akka.cli.cloudflow.kubeclient.KubeClient
import akka.cli.cloudflow.commands.List

final case class ListExecution(l: List, client: KubeClient, logger: CliLogger)
    extends Execution[ListResult]
    with WithProtocolVersion {
  def run(): Try[ListResult] = {
    logger.info("Executing command List")
    for {
      _ <- validateProtocolVersion(client)
      res <- client.listCloudflowApps(l.namespace)
    } yield {
      ListResult(res)
    }
  }
}
