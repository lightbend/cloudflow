/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.commands.Scale
import akka.cli.cloudflow.kubeclient.KubeClient
import akka.cli.cloudflow.{ CliException, CliLogger, Execution, ScaleResult }

import scala.util.Try

final case class ScaleExecution(s: Scale, client: KubeClient, logger: CliLogger)
    extends Execution[ScaleResult]
    with WithProtocolVersion
    with WithUpdateReplicas {
  def run(): Try[ScaleResult] = {
    logger.info("Executing command Status")
    for {
      _ <- validateProtocolVersion(client, logger)
      namespace = s.namespace.getOrElse(s.cloudflowApp)

      currentAppCrOpt <- client.readCloudflowApp(s.cloudflowApp, namespace)

      currentAppCr = currentAppCrOpt.getOrElse(throw CliException(s"Application ${s.cloudflowApp} not found"))
      applicationCr <- updateReplicas(currentAppCr, s.scales)

      _ <- client.updateCloudflowApp(applicationCr, namespace)
    } yield {
      ScaleResult()
    }
  }
}
