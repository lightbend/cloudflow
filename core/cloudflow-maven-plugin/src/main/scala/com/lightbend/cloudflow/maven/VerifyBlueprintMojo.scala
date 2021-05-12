package com.lightbend.cloudflow.maven

import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.{ AbstractMojo, BuildPluginManager }
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.MavenProject

import scala.collection.JavaConverters._

@Mojo(
  name = "verify-blueprint",
  aggregator = false,
  requiresDependencyResolution = ResolutionScope.COMPILE,
  requiresDependencyCollection = ResolutionScope.COMPILE,
  defaultPhase = LifecyclePhase.PACKAGE)
class VerifyBlueprintMojo extends AbstractMojo {

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
      try {
        CloudflowAggregator.getCR(
          CloudflowAggregator
            .generateLocalCR(projectId = projectId, version = version, allProjects = allProjects, log = getLog()))

        getLog().info("Blueprint validated!")
      } catch {
        case ex: Throwable =>
          getLog().error(ex.getMessage)
          throw ex
      }
    }
  }

}
