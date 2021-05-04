/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.cli.cloudflow._
import akka.cli.cloudflow.commands.{ format, Command }
import akka.cli.cloudflow.kubeclient.KubeClientFabric8
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory

class TestingCli(val client: KubernetesClient, logger: CliLogger = new CliLogger(None))
    extends Cli(None, (_, _) => new KubeClientFabric8(None, _ => client)(logger))(logger) {

  val testLogger = LoggerFactory.getLogger(this.getClass)

  var lastResult: String = ""

  def transform[T](cmd: Command[T], res: T): T = {
    val newResult = cmd.toString + "\n" + res.asInstanceOf[Result].render(format.Table)
    if (newResult != lastResult) {
      lastResult = newResult
      testLogger.debug(newResult)
    }
    res
  }

  def handleError[T](cmd: Command[T], ex: Throwable): Unit = {
    testLogger.warn(s"Error executing command ${cmd}", ex)
  }
}
