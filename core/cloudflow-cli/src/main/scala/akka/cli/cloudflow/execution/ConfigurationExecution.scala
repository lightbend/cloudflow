/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import scala.util.Try
import akka.cli.cloudflow.{ CliException, CliLogger, ConfigurationResult, Execution }
import akka.cli.cloudflow.commands.Configuration
import akka.cli.cloudflow.kubeclient.KubeClient
import com.typesafe.config.ConfigFactory

final case class ConfigurationExecution(c: Configuration, client: KubeClient, logger: CliLogger)
    extends Execution[ConfigurationResult]
    with WithProtocolVersion {
  def run(): Try[ConfigurationResult] = {
    logger.info("Executing command Configuration")
    for {
      _ <- validateProtocolVersion(client, c.namespace)
      res <- client.getAppInputSecret(c.cloudflowApp, c.namespace.getOrElse(c.cloudflowApp))
      config <- Try { ConfigFactory.parseString(res) }.recover {
        case ex => throw CliException("Failed to parse the current configuration", ex)
      }
    } yield {
      ConfigurationResult(config)
    }
  }
}
