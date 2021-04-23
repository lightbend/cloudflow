/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.CliLogger

import java.io.File
import scala.io.Source
import scala.util.{ Failure, Success, Try }

object PluginExecution {

  final val DEPLOY = "DEPLOY"
  // final val CONFIG = "CONFIG"
  final val UNDEPLOY = "UNDEPLOY"

  private def readPluginContent(f: Option[File]): Option[String] = {
    f.map { file =>
      val source = Source.fromFile(file)
      try {
        source.getLines().mkString
      } finally {
        source.close()
      }
    }
  }

  def execute(
      plugin: Option[File],
      operation: String,
      appName: String,
      configs: Option[String],
      logger: CliLogger): Try[Unit] = {
    readPluginContent(plugin) match {
      case Some(js) =>
        logger.info(s"Executing plugin in file: ${plugin.get.getAbsolutePath()}")
        var context: org.graalvm.polyglot.Context = null
        Try {
          context = org.graalvm.polyglot.Context.create()
          context.getBindings("js").putMember("operation", operation)
          context.getBindings("js").putMember("application", appName)
          configs match {
            case Some(c) =>
              context.getBindings("js").putMember("configs", c)
            case _ =>
          }
          context.eval("js", js)
          ()
        }.recoverWith {
          case ex: Throwable =>
            logger.warn(s"Plugin execution failed", ex)
            if (context != null) context.close()
            Failure(ex)
        }
      case _ =>
        Success(())
    }
  }
}
