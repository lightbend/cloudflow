/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.cloudflow.buildtool

import java.io.File
import cloudflow.blueprint.deployment._

object PrintUtils {

  // Banner decorators
  val infoBanner = banner('-') _
  val warningBanner = banner('!') _

  // Needs to match StreamletAttribute
  final val configPrefix = "cloudflow.internal"
  final def configSection: String = s"$configPrefix.$attributeName"
  final def configPath = s"$configSection.$configKey"
  // Needs to match ServerAttribute
  final val attributeName = "server"
  final val configKey = "container-port"

  def banner(bannerChar: Char)(name: String)(message: Any): Unit = {
    val title = s" $name "
    val bannerLength = 80
    val sideLength = (bannerLength - title.size) / 2
    val side = List.fill(sideLength)(bannerChar).mkString("")
    val bottom = List.fill(bannerLength)(bannerChar).mkString("")

    println(side + title + side)
    println(message.toString)
    println(bottom + "\n")
  }

  def printInfo(
      descriptors: Iterable[(String, RuntimeDescriptor)],
      outputFolder: File,
      topics: Seq[String],
      localConfMsg: String): Unit = {
    val streamletInfoPerProject = descriptors.map {
      case (pid, rd) => (pid, rd.outputFile, streamletInfo(rd.appDescriptor))
    }
    val streamletReport = streamletInfoPerProject.map {
      case (pid, outputFile, streamletInfo) =>
        s"$pid - output file: ${outputFile.toURI.toString}\n\n" + streamletInfo.foldLeft("") {
          case (agg, str) => s"$agg\t$str\n"
        }
    }
    infoBanner("Streamlets per project")(streamletReport.mkString("\n"))
    infoBanner("Topics")(topics.map(t => s"[$t]").mkString("\n"))
    infoBanner("Local Configuration")(localConfMsg)
    infoBanner("Output")(s"Pipeline log output available in folder: " + outputFolder)
  }

  def streamletInfo(descriptor: ApplicationDescriptor): Seq[String] = {
    val streamletInstances: Seq[StreamletInstance] = descriptor.streamlets.sortBy(_.name)

    streamletInstances.map { streamlet =>
      val streamletDeployment = descriptor.deployments.find(_.streamletName == streamlet.name)
      val serverPort: Option[Int] = streamletDeployment.flatMap { sd =>
        if (sd.config.hasPath(configPath)) {
          Some(sd.config.getInt(configPath))
        } else {
          None
        }
      }

      def newLineIfNotEmpty(s: String): String = if (s.nonEmpty) s"\n$s" else s

      val volumeMounts = streamlet.descriptor.volumeMounts
        .map { mount =>
          s"\t- mount [${mount.name}] available at [${mount.path}]"
        }
        .mkString("\n")

      val endpointMessage = serverPort.map(port => s"\t- HTTP port [$port]").getOrElse("")
      s"${streamlet.name} [${streamlet.descriptor.className}]" +
      newLineIfNotEmpty(endpointMessage) +
      newLineIfNotEmpty(volumeMounts)
    }
  }

}
