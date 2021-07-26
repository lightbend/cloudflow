/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.commands.Configure
import akka.cli.cloudflow.kubeclient.KubeClient
import akka.cli.cloudflow.{ Cli, CliException, CliLogger, ConfigureResult, Execution }

import scala.util.Try

final case class ConfigureExecution(c: Configure, client: KubeClient, logger: CliLogger)
    extends Execution[ConfigureResult]
    with WithProtocolVersion
    with WithConfiguration {
  def run(): Try[ConfigureResult] = {
    logger.info("Executing command Configure")
    for {
      _ <- validateProtocolVersion(client)

      currentCr <- client.readCloudflowApp(c.cloudflowApp).map {
        _.getOrElse(throw CliException(s"Cloudflow application ${c.cloudflowApp} not found in the cluster"))
      }

      logbackContent = readLogbackContent(c.logbackConfig)
      // configuration validation
      (cloudflowConfig, configStr) <- generateConfiguration(
        c.aggregatedConfig,
        currentCr,
        logbackContent,
        () => client.getPvcs(namespace = currentCr.getSpec().appId))

      // streamlets configurations
      streamletsConfigs <- streamletsConfigs(
        currentCr,
        cloudflowConfig,
        c.microservices,
        () => client.getKafkaClusters(None).map(parseValues))

      uid <- client.uidCloudflowApp(currentCr.getSpec().appId)
      _ <- client.configureCloudflowApp(currentCr.getSpec().appId, uid, configStr, logbackContent, streamletsConfigs)
    } yield {
      ConfigureResult()
    }
  }
}
