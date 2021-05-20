/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.maven

import cloudflow.buildtool.AppLayout
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.{ AbstractMojo, BuildPluginManager }
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.MavenProject

import scala.collection.JavaConverters._

@Mojo(
  name = "app-layout",
  aggregator = false,
  requiresDependencyResolution = ResolutionScope.COMPILE,
  requiresDependencyCollection = ResolutionScope.COMPILE,
  defaultPhase = LifecyclePhase.PACKAGE)
class AppLayoutMojo extends AbstractMojo {

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

      val cr = CloudflowProjectAggregator.getCR(
        CloudflowProjectAggregator
          .generateLocalCR(projectId = projectId, version = version, allProjects = allProjects, log = getLog()))

      val res = AppLayout.getAppLayout(AppLayout.resolveConnections(cr.spec))

      getLog.info("App Layout:")
      println(res)
    }
  }

}
