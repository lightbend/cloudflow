/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.maven

import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.{ AbstractMojo, BuildPluginManager }
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.MavenProject

import java.io.File
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

      try {
        val cr = CloudflowProjectAggregator.generateCR(
          projectId = projectId,
          version = version,
          allProjects = allProjects,
          log = getLog())

        val destFile = new File(topLevel.getBuild.getDirectory, projectId + ".json")
        FileUtil.writeFile(destFile, cr)
        getLog().info("Deploy your Cloudflow Application with:")
        getLog().info(s"kubectl cloudflow deploy ${destFile.getAbsolutePath}")
      } catch {
        case ex: Throwable =>
          getLog().error(ex.getMessage)
          throw ex
      }
    }
  }

}
