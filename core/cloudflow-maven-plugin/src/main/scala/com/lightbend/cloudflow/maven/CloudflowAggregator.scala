package com.lightbend.cloudflow.maven

import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject

import spray.json._
import cloudflow.blueprint.deployment._
import cloudflow.blueprint.deployment.CloudflowCRFormat._

import java.io.File
import java.net.URLEncoder
import java.nio.file.Paths

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

  def getCR(topLevel: MavenProject) = {
    val crFile = new File(topLevel.getBuild.getDirectory, topLevel.getName + ".json")

    if (crFile.exists()) {

      val cr = FileUtil
        .readLines(crFile)
        .mkString("\n")
        .parseJson
        .convertTo[CloudflowCR]

      Some(cr)
    } else {
      None
    }
  }

}
