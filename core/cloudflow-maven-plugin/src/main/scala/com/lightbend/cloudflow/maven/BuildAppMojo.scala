package com.lightbend.cloudflow.maven

import buildinfo.BuildInfo
import cloudflow.cr.Generator
import com.typesafe.config.{ Config, ConfigFactory, ConfigValue }
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.{ AbstractMojo, BuildPluginManager }
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.MavenProject

import java.io.File
import java.net.URLEncoder
import java.nio.file.{ Path, Paths }
import scala.collection.JavaConverters._

@Mojo(
  name = "build-app",
  aggregator = true,
  requiresDependencyResolution = ResolutionScope.COMPILE,
  requiresDependencyCollection = ResolutionScope.COMPILE,
  defaultPhase = LifecyclePhase.PACKAGE)
class BuildAppMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  var mavenProject: MavenProject = _

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  var mavenSession: MavenSession = _

  @Component
  var pluginManager: BuildPluginManager = _

  def getBlueprint(project: MavenProject) = {
    val blueprintFile =
      Paths.get(project.getBasedir.getAbsolutePath, "src", "main", "blueprint", "blueprint.conf").toFile
    if (blueprintFile.exists()) {
      Some(blueprintFile)
    } else {
      None
    }
  }

  def execute(): Unit = {
    val topLevel = mavenSession.getTopLevelProject
    val projectId = topLevel.getName
    val version = topLevel.getVersion

    val allProjects = mavenSession.getAllProjects.asScala.sortBy(_.getName)

    if (allProjects.last == mavenProject) {

      val (streamlets, images, blueprintFile) = {
        allProjects.foldLeft(Map.empty[String, Config], Map.empty[String, String], Option.empty[File]) {
          case (acc, project) =>
            try {

              getLog.info(s"BuildApp is analyzing $project")

              val rawStreamlets =
                FileUtil.readLines(new File(project.getBuild.getDirectory, Constants.STREAMLETS_FILE))

              val streamlets: Map[String, Config] = rawStreamlets.map { k =>
                val file = new File(project.getBuild.getDirectory, URLEncoder.encode(k, "UTF-8"))
                val config = ConfigFactory
                  .parseFile(file)
                  .resolve()
                k -> config
              }.toMap

              val image =
                FileUtil.readLines(new File(project.getBuild.getDirectory, Constants.DOCKER_IMAGE_FILE)).mkString.trim

              val images = streamlets.keys.map(streamlet => streamlet -> image)

              ((acc._1 ++ streamlets), (acc._2 ++ images), getBlueprint(project).orElse(acc._3))
            } catch {
              case ex: Throwable =>
                getLog().warn(s"No streamlets found in project $project")
                (acc._1, acc._2, getBlueprint(project).orElse(acc._3))
            }
        }
      }

      getLog.info(s"Last project(${mavenProject.getName}) building the actual CR")

      getLog.info(s"Blueprint found: ${blueprintFile.map(_.getAbsolutePath).getOrElse("none")}")

      val blueprintStr = FileUtil.readLines(blueprintFile.get).mkString("")
      val blueprint = ConfigFactory.parseString(blueprintStr).getObject("blueprint.streamlets").asScala

      val finalImages = images.map {
        case (k, v) =>
          blueprint
            .find { b => b._2.unwrapped() == k }
            .head
            ._1 -> v
      }

      val cr = Generator.generate(
        projectId = projectId,
        version = version,
        blueprintStr = blueprintStr,
        streamlets = streamlets,
        dockerImages = finalImages)

      val destFile = new File(topLevel.getBuild.getDirectory, projectId + ".json")
      FileUtil.writeFile(destFile, cr)
      getLog().info("Deploy your Cloudflow Application with:")
      getLog().info(s"kubectl cloudflow ${destFile.getAbsolutePath}")
    }
  }

}
