/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.maven

import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.{ AbstractMojo, BuildPluginManager }
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.MavenProject

import java.io.File

@Mojo(
  name = "push-images",
  aggregator = false,
  requiresDependencyResolution = ResolutionScope.COMPILE,
  requiresDependencyCollection = ResolutionScope.COMPILE,
  defaultPhase = LifecyclePhase.PACKAGE)
class PushImagesMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  var mavenProject: MavenProject = _

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  var mavenSession: MavenSession = _

  @Component
  var pluginManager: BuildPluginManager = _

  private def pushImage(project: MavenProject): Boolean = {
    import org.twdata.maven.mojoexecutor.MojoExecutor._

    val config = project.getGoalConfiguration("io.fabric8", "docker-maven-plugin", null, "push")

    if (config != null) {
      config.addChild(element(name("outputFile"), "${project.build.directory}/docker-push-output.log").toDom)

      executeMojo(
        plugin(groupId("io.fabric8"), artifactId("docker-maven-plugin"), version("0.36.0")),
        goal("push"),
        config,
        executionEnvironment(project, mavenSession, pluginManager))

      true
    } else {
      false
    }
  }

  private val PushedImageDigestSha256 = ".* digest: sha256:([0-9a-f]+) .*".r
  private val FullImageName = ".*The push refers to repository \\[(.+)\\].*".r

  def execute(): Unit = {
    if (pushImage(mavenProject)) {

      val outputFile = new File(mavenProject.getBuild.getDirectory, Constants.DOCKER_IMAGE_FILE)

      val lines = FileUtil.readLines(new File(mavenProject.getBuild.getDirectory, "docker-push-output.log"))

      val imageName = lines.collect {
        case FullImageName(name) => name
      }.lastOption

      val imageDigest = lines.collect {
        case PushedImageDigestSha256(digest) => s"sha256:$digest"
      }.lastOption

      (imageName, imageDigest) match {
        case (Some(name), Some(digest)) => FileUtil.writeFile(outputFile, s"${name}@${digest}")
        case _                          => throw new Exception("Cannot parse pushed image logs")
      }
    } else {
      getLog.info("no docker image defined")
    }
  }

}
