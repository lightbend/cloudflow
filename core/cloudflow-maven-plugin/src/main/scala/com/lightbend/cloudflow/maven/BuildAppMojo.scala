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
  aggregator = false,
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

  def execute(): Unit = {
    val topLevel = mavenSession.getTopLevelProject
    val projectId = topLevel.getName
    val version = topLevel.getVersion

    val allProjects = mavenSession.getAllProjects.asScala

    if (allProjects.last == mavenProject) {

      val streamletsPerProject = CloudflowAggregator.getStreamlets(allProjects, getLog())
      val imagesPerProject = CloudflowAggregator.getImages(allProjects, getLog())
      val blueprintFile = CloudflowAggregator.getBlueprint(allProjects, getLog())

      val streamlets = streamletsPerProject.foldLeft(Map.empty[String, Config]) { case (acc, (_, v)) => acc ++ v }
      val images = streamletsPerProject.foldLeft(Map.empty[String, String]) {
        case (acc, (k, v)) => acc ++ v.keys.map { s => s -> imagesPerProject(k) }
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

      try {
        val cr = Generator.generate(
          projectId = projectId,
          version = version,
          blueprintStr = blueprintStr,
          streamlets = streamlets,
          dockerImages = finalImages)

        val destFile = new File(topLevel.getBuild.getDirectory, projectId + ".json")
        FileUtil.writeFile(destFile, cr)
        getLog().info("Deploy your Cloudflow Application with:")
        getLog().info(s"kubectl cloudflow deploy ${destFile.getAbsolutePath}")
      } catch {
        case ex: Throwable =>
          getLog().error(ex.getMessage)
      }
    }
  }

}
