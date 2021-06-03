/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.cr

import com.typesafe.config.{ Config, ConfigFactory, ConfigRenderOptions }

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

  def scanProject(projectId: String, classpath: List[String]): ExtractResult = {
    val scanConfig =
      DescriptorExtractor.ScanConfiguration(projectId = projectId, classpathUrls = classpath.map(new URL(_)).toArray)

    DescriptorExtractor.scan(scanConfig)
  }

  def generate(
      projectId: String,
      version: String,
      blueprintStr: String,
      streamlets: Map[String, Config],
      dockerImages: Map[String, String]): String = {

    val streamletDescriptors = streamlets.map {
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
      val problemsMsg = blueprint.problems
        .map { p =>
          BlueprintProblem.toMessage(p)
        }
        .mkString("\n")
      throw new Exception(s"Found problems in the blueprint:\n${problemsMsg}")
    }
  }

  final case class GeneratorConfig(
      name: String = null,
      version: String = null,
      blueprint: File = null,
      classpath: File = null,
      images: Map[String, String] = Map())

  private lazy val builder = OParser.builder[GeneratorConfig]
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
    OParser.parse(parser, args, GeneratorConfig()) match {
      case Some(config) =>
        (for {
          cp <- Try(Source.fromFile(config.classpath).getLines.toList)
          streamlets <- scanProject(projectId = config.name, classpath = cp).toTry
          res <- Try {
            generate(
              projectId = config.name,
              version = config.version,
              blueprintStr = Source.fromFile(config.blueprint).mkString,
              streamlets,
              dockerImages = config.images)
          }
        } yield res) match {
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
