/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.maven

import cloudflow.cr.Generator
import com.typesafe.config.{ Config, ConfigRenderOptions }
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.{ AbstractMojo, BuildPluginManager }
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.MavenProject

import java.io.File
import java.net.URLEncoder

@Mojo(
  name = "extract-streamlets",
  aggregator = false,
  requiresDependencyResolution = ResolutionScope.COMPILE,
  requiresDependencyCollection = ResolutionScope.COMPILE,
  defaultPhase = LifecyclePhase.PACKAGE)
class ExtractStreamletsMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  var mavenProject: MavenProject = _

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  var mavenSession: MavenSession = _

  @Component
  var pluginManager: BuildPluginManager = _

  val dependencyFile = "${project.build.directory}/classpath.txt"

  private def createDependencyListFile(project: MavenProject) = {
    import org.twdata.maven.mojoexecutor.MojoExecutor._
    executeMojo(
      plugin(groupId("org.apache.maven.plugins"), artifactId("maven-dependency-plugin"), version("3.1.2")),
      goal("build-classpath"),
      configuration(
        element(name("outputFile"), dependencyFile),
        element(name("pathSeparator"), Constants.PATH_SEPARATOR),
        element(name("regenerateFile"), "true"),
        element(name("outputAbsoluteArtifactFilename"), "true")),
      executionEnvironment(project, mavenSession, pluginManager))
  }

  def showStreamlet(s: (String, Config)): String = {
    s"[name: ${s._1}, config: ${s._2.root().render(ConfigRenderOptions.concise())}]"
  }

  def execute(): Unit = {
    val topLevel = mavenSession.getTopLevelProject
    val projectId = topLevel.getName

    createDependencyListFile(mavenProject)

    val allDeps =
      CloudflowProjectAggregator.classpathByProject(mavenProject).map(_.toString).distinct.filterNot(_.isEmpty)

    FileUtil.writeFile(
      new File(mavenProject.getBuild.getDirectory, Constants.FULL_CLASSPATH),
      allDeps.map(_.toString).mkString(Constants.PATH_SEPARATOR))

    val extractResult = Generator
      .scanProject(projectId = projectId, classpath = allDeps)

    getLog().info(s"streamlets found: ${extractResult.descriptors.map(showStreamlet).mkString(",")}")

    val streamlets = extractResult.toTry
      .fold(e => throw e, identity)

    val res = streamlets
      .map {
        case (k, v) =>
          val content =
            v.root().render(ConfigRenderOptions.concise().setComments(false).setOriginComments(false).setJson(true))
          FileUtil.writeFile(new File(mavenProject.getBuild.getDirectory, URLEncoder.encode(k, "UTF-8")), content)
          k
      }
      .mkString("\n")

    val outputFile = new File(mavenProject.getBuild.getDirectory, Constants.STREAMLETS_FILE)

    FileUtil.writeFile(outputFile, res)
  }
}
