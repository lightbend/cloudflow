/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import java.io.File

import akka.cli.cloudflow.commands.Command
import akka.cli.cloudflow.kubeclient.KubeClient

class TestingCli(kubeClientFactory: (Option[File], CliLogger) => KubeClient)
    extends Cli(None, kubeClientFactory)(new CliLogger(None)) {

  def transform[T](cmd: Command[T], res: T): T = res

  def handleError[T](cmd: Command[T], ex: Throwable): Unit = ()
}
