package com.lightbend.cloudflow.maven

import cloudflow.blueprint.deployment.CloudflowCRFormat._
import cloudflow.blueprint.deployment._
import cloudflow.cr.Generator
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.maven.artifact.Artifact
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import spray.json._

import java.io.File
import java.net.{ URL, URLEncoder }
import java.nio.file.Paths
import scala.collection.JavaConverters._
import scala.util.Try

object CloudflowAggregator {

  private def findBlueprint(project: MavenProject) = {
    val blueprintFile =
      Paths.get(project.getBasedir.getAbsolutePath, "src", "main", "blueprint", "blueprint.conf").toFile
    if (blueprintFile.exists()) {
      Some(blueprintFile)
    } else {
      None
    }
  }

  def getBlueprint(allProjects: Seq[MavenProject], log: Log): Option[File] = {
    allProjects.foldLeft(Option.empty[File]) {
      case (acc, project) =>
        log.info(s"checking blueprint in $project")

        findBlueprint(project).orElse(acc)
    }
  }

  def getStreamlets(allProjects: Seq[MavenProject], log: Log) = {
    allProjects.foldLeft(Map.empty[String, Map[String, Config]]) {
      case (acc, project) =>
        try {

          log.info(s"extracting streamlets $project")

          val rawStreamlets =
            FileUtil.readLines(new File(project.getBuild.getDirectory, Constants.STREAMLETS_FILE))

          val streamlets: Map[String, Config] = rawStreamlets.map { k =>
            val file = new File(project.getBuild.getDirectory, URLEncoder.encode(k, "UTF-8"))
            val config = ConfigFactory
              .parseFile(file)
              .resolve()
            k -> config
          }.toMap

          (acc + (project.getName -> streamlets))
        } catch {
          case ex: Throwable =>
            log.warn(s"No streamlets found in project $project")
            (acc)
        }
    }
  }

  def getImages(allProjects: Seq[MavenProject], log: Log) = {
    allProjects.foldLeft(Map.empty[String, String]) {
      case (acc, project) =>
        try {

          log.info(s"extracting image names $project")

          val image =
            FileUtil.readLines(new File(project.getBuild.getDirectory, Constants.DOCKER_IMAGE_FILE)).mkString.trim

          (acc + (project.getName -> image))
        } catch {
          case ex: Throwable =>
            log.warn(s"No images found in project $project")
            (acc)
        }
    }
  }

  def getCR(topLevel: MavenProject): Option[CloudflowCR] = {
    val crFile = new File(topLevel.getBuild.getDirectory, topLevel.getName + ".json")

    if (crFile.exists()) {

      val cr = getCR(
        FileUtil
          .readLines(crFile)
          .mkString("\n"))

      Some(cr)
    } else {
      None
    }
  }

  def getCR(str: String): CloudflowCR = {
    str.parseJson.convertTo[CloudflowCR]
  }

  private def readBlueprint(blueprintFile: Option[File]) = {
    val blueprintStr = FileUtil.readLines(blueprintFile.get).mkString("\n")

    (blueprintStr, ConfigFactory.parseFile(blueprintFile.get).getObject("blueprint.streamlets").asScala.toMap)
  }

  def generateLocalCR(projectId: String, version: String, allProjects: Seq[MavenProject], log: Log) = {
    val streamletsPerProject = CloudflowAggregator.getStreamlets(allProjects, log)

    val streamlets = streamletsPerProject.foldLeft(Map.empty[String, Config]) { case (acc, (_, v)) => acc ++ v }

    val (blueprintStr, blueprint) = readBlueprint(CloudflowAggregator.getBlueprint(allProjects, log))

    val placeholderImages = blueprint.map { case (k, _) => k -> "placeholder" }

    Generator.generate(
      projectId = projectId,
      version = version,
      blueprintStr = blueprintStr,
      streamlets = streamlets,
      dockerImages = placeholderImages)
  }

  def generateCR(projectId: String, version: String, allProjects: Seq[MavenProject], log: Log) = {
    val streamletsPerProject = CloudflowAggregator.getStreamlets(allProjects, log)
    val imagesPerProject = CloudflowAggregator.getImages(allProjects, log)

    val streamlets = streamletsPerProject.foldLeft(Map.empty[String, Config]) { case (acc, (_, v)) => acc ++ v }
    val images = streamletsPerProject.foldLeft(Map.empty[String, String]) {
      case (acc, (k, v)) => acc ++ v.keys.map { s => s -> imagesPerProject(k) }
    }
    val (blueprintStr, blueprint) = readBlueprint(CloudflowAggregator.getBlueprint(allProjects, log))

    val finalImages = images
      .map {
        case (k, v) =>
          blueprint
            .find { b => b._2.unwrapped() == k }
            .headOption
            .map(_._1 -> v)
      }
      .flatten
      .toMap

    Generator.generate(
      projectId = projectId,
      version = version,
      blueprintStr = blueprintStr,
      streamlets = streamlets,
      dockerImages = finalImages)
  }

  def classpathByProject(project: MavenProject): List[URL] = {
    val deps = FileUtil
      .readLines(new File(project.getBuild.getDirectory, "classpath.txt"))
      .mkString
      .split(Constants.PATH_SEPARATOR)
      .map(d => new File(d).getAbsoluteFile.toURI.toURL)
      .toList

    val artifacts = project.getArtifacts.asScala.map { a: Artifact =>
        a.getFile.toURI.toURL
      }.toList ++ Try {
        project.getArtifact.getFile.toURI.toURL
      }.toOption.toList

    (deps ++ artifacts)
  }

}
