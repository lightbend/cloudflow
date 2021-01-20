/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import java.io.File

import scala.util.{ Failure, Try }
import akka.cli.cloudflow.Cli.defaultKubeClient
import akka.cli.cloudflow.commands.Command
import kubeclient._

object Cli {

  def defaultKubeClient(config: Option[File], logger: CliLogger) =
    new KubeClientFabric8(config)(logger)

  val CompatibleProtocolVersion = "4"
  val ProtocolVersion = "5"

  val SupportedApplicationDescriptorVersion = 5

  val RequiredSparkVersion = "v1beta2"

  val RequiredFlinkVersion = "v1beta1"
}

abstract class Cli(kubeConfig: Option[File], kubeClientFactory: (Option[File], CliLogger) => KubeClient)(
    implicit logger: CliLogger) {
  private lazy val kubeClient = kubeClientFactory(kubeConfig, logger)

  def transform[T](cmd: Command[T], res: T): T

  def handleError[T](cmd: Command[T], ex: Throwable): Unit

  def run[T](cmd: Command[T]): Try[T] = {
    logger.trace(s"Cli run command: $cmd")
    (for {
      res <- cmd.execution(kubeClient, logger).run()
    } yield {
      transform(cmd, res)
    }).recoverWith {
      case ex =>
        logger.error("Failure", ex)
        handleError(cmd, ex)
        Failure[T](ex)
    }
  }

}

class PrintingCli(
    kubeConfig: Option[File],
    kubeClientFactory: (Option[File], CliLogger) => KubeClient = defaultKubeClient(_, _))(implicit logger: CliLogger)
    extends Cli(kubeConfig, kubeClientFactory)(logger) {

  def transform[T](cmd: Command[T], res: T): T = {
    logger.info(s"Action executed successfully, result: $res")
    val output = cmd.render(res)
    if (!output.isEmpty) {
      Console.println(output)
    }
    res
  }

  def handleError[T](cmd: Command[T], ex: Throwable): Unit = {
    Console.err.println(s"Error: ${ex.getMessage()}")
  }

}
