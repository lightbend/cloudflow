/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.commands.UpdateCredentials
import akka.cli.cloudflow.kubeclient.KubeClient
import akka.cli.cloudflow.{ CliLogger, Execution, UpdateCredentialsResult }

import scala.util.Try

final case class UpdateCredentialsExecution(u: UpdateCredentials, client: KubeClient, logger: CliLogger)
    extends Execution[UpdateCredentialsResult]
    with WithProtocolVersion {
  def run(): Try[UpdateCredentialsResult] = {
    logger.info("Executing command UpdateCredentials")
    for {
      _ <- validateProtocolVersion(client)
      _ <- client.createNamespace(u.cloudflowApp)
      _ <- client.createImagePullSecret(
        namespace = u.cloudflowApp,
        dockerRegistryURL = u.dockerRegistry,
        dockerUsername = u.username,
        dockerPassword = u.password)
    } yield {
      UpdateCredentialsResult()
    }
  }
}
