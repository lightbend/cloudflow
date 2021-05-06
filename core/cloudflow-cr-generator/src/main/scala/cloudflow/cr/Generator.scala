/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.cr

import com.typesafe.config.{ ConfigFactory, ConfigRenderOptions }

import java.io.File
import java.net.URL
import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.{ Failure, Success, Try }

import scopt.OParser
import spray.json._
import cloudflow.blueprint._
import cloudflow.blueprint.StreamletDescriptorFormat._
import cloudflow.blueprint.deployment._
import cloudflow.extractor._

object Generator {

  val cloudflowVersion = BuildInfo.version

  private def makeCR(appDescriptor: ApplicationDescriptor, version: String): CloudflowCR =
    CloudflowCR(
      apiVersion = "cloudflow.lightbend.com/v1alpha1",
      kind = "CloudflowApplication",
      metadata = Metadata(
        annotations = Map("com.lightbend.cloudflow/created-by-cli-version" -> version),
        labels = Map(
          "app.kubernetes.io/managed-by" -> "cloudflow",
          "app.kubernetes.io/part-of" -> appDescriptor.appId,
          "com.lightbend.cloudflow/app-id" -> appDescriptor.appId),
        name = appDescriptor.appId),
      appDescriptor)

  def generate(
      projectId: String,
      version: String,
      blueprintStr: String,
      classpath: List[String],
      dockerImages: Map[String, String]): String = {
    val scanConfig =
      DescriptorExtractor.ScanConfiguration(projectId = projectId, classpathUrls = classpath.map(new URL(_)).toArray)

    val extractedStreamlets = DescriptorExtractor.scan(scanConfig)

    val streamletDescriptors = extractedStreamlets.map {
      case (_, configDescriptor) =>
        configDescriptor
          .root()
          .render(ConfigRenderOptions.concise())
          .parseJson
          .convertTo[cloudflow.blueprint.StreamletDescriptor]
    }

    val blueprintConfig = ConfigFactory.parseString(blueprintStr).resolve()

    val blueprint = Blueprint.parseConfig(blueprintConfig, streamletDescriptors.toVector)
    if (blueprint.problems.isEmpty) {
      val appDescriptor = {
        val appVersion = version
        val agentPathsMap = Map("prometheus" -> "/prometheus/jmx_prometheus_javaagent.jar")
        val libraryVersion = cloudflowVersion

        ApplicationDescriptor(
          appId = projectId,
          appVersion = version,
          (k) => dockerImages(k),
          blueprint.verified.right.get,
          agentPathsMap,
          cloudflowVersion)
      }

      import cloudflow.blueprint.deployment.CloudflowCRFormat._
      val cloudflowCr = makeCR(appDescriptor, version)
      cloudflowCr.toJson.compactPrint
    } else {
      throw new Exception(s"Found errors: ${blueprint.problems.mkString}")
    }
  }

  final case class Config(
      name: String = null,
      version: String = null,
      blueprint: File = null,
      classpath: File = null,
      images: Map[String, String] = Map())

  private lazy val builder = OParser.builder[Config]
  private lazy val parser = {
    import builder._
    OParser.sequence(
      programName("cloudflow-cr-generator"),
      head("cloudflow-cr-generator", cloudflowVersion),
      opt[String]('n', "name")
        .action((x, c) => c.copy(name = x))
        .text("Name of the Cloudflow Application"),
      opt[String]('v', "version")
        .action((x, c) => c.copy(version = x))
        .text("Version of the Cloudflow Application"),
      opt[File]('b', "blueprint")
        .action((x, c) => c.copy(blueprint = x))
        .text("Blueprint of the Cloudflow Application"),
      opt[File]('c', "classpath")
        .action((x, c) => c.copy(classpath = x))
        .text("Classpath of the Cloudflow Application"),
      opt[Map[String, String]]('i', "images")
        .action((x, c) => c.copy(images = c.images ++ x))
        .text("Docker images of the Cloudflow Application"),
      checkConfig(
        c =>
          if (c.name == null || c.version == null || c.blueprint == null || c.classpath == null)
            failure("All the options should be filled in")
          else success))
  }

  def main(args: Array[String]): Unit = {

    OParser.parse(parser, args, Config()) match {
      case Some(config) =>
        Try {
          generate(
            projectId = config.name,
            version = config.version,
            blueprintStr = Source.fromFile(config.blueprint).mkString,
            classpath = Source.fromFile(config.classpath).getLines.toList,
            dockerImages = config.images)
        } match {
          case Success(result) =>
            Console.out.println(result)
            System.exit(0)
          case Failure(ex) =>
            Console.err.println(ex.getMessage)
            System.exit(1)
        }
      case _ =>
        System.exit(1)
    }
  }

}
