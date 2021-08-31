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
      _ <- validateProtocolVersion(client, logger)
      namespace = c.namespace.getOrElse(c.cloudflowApp)

      currentCr <- client.readCloudflowApp(c.cloudflowApp, namespace).map {
        _.getOrElse(throw CliException(s"Cloudflow application ${c.cloudflowApp} not found in the cluster"))
      }

      logbackContent = readLogbackContent(c.logbackConfig)
      // configuration validation
      (cloudflowConfig, configStr) <- generateConfiguration(
        c.aggregatedConfig,
        currentCr,
        logbackContent,
        () => client.getPvcs(namespace = namespace))

      // streamlets configurations
      streamletsConfigs <- streamletsConfigs(
        currentCr,
        cloudflowConfig,
        () => client.getKafkaClusters(namespace = c.operatorNamespace).map(parseValues))

      uid <- client.uidCloudflowApp(currentCr.spec.appId, namespace)
      _ <- client.configureCloudflowApp(
        currentCr.spec.appId,
        namespace,
        uid,
        configStr,
        logbackContent,
        streamletsConfigs)
    } yield {
      ConfigureResult()
    }
  }
}
