/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.sbt

import java.nio.charset.StandardCharsets._
import java.util.Base64
import java.util.zip.Deflater
import java.io.ByteArrayOutputStream

import scala.util.control._

import sbt._
import sbt.Keys._
import sbtdocker._
import sbtdocker.DockerKeys._
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.archetypes._
import com.lightbend.sbt.javaagent.JavaAgent
import java.io.File
import spray.json._
import com.lightbend.sbt.javaagent.JavaAgent.JavaAgentKeys.resolvedJavaAgents

import cloudflow.blueprint.deployment.ApplicationDescriptorJsonFormat._
import cloudflow.sbt.CloudflowKeys._

object ImagePlugin extends AutoPlugin {
  val AppRunner: String                = "akka-entrypoint.sh"
  val AppHome                          = "${app_home}"
  val AppTargetDir: String             = "/app"
  val appTargetSubdir: String ⇒ String = dir ⇒ s"$AppTargetDir/$dir"
  val AppJarsDir: String               = "app-jars"
  val DepJarsDir: String               = "dep-jars"
  val optAppDir                        = "/opt/cloudflow/"

  override def requires =
    CommonSettingsAndTasksPlugin &&
      JavaAppPackaging && JavaAgent &&
      sbtdocker.DockerPlugin

  private def agentMappings = Def.task[Seq[(File, String, String)]] {
    resolvedJavaAgents.value.filter(_.agent.scope.dist).map { resolved ⇒
      (resolved.artifact,
       Project.normalizeModuleID(resolved.agent.name) + File.separator + resolved.artifact.name,
       resolved.agent.arguments)
    }
  }

  private def agentJavaOptions = Def.task[Seq[String]] {
    agentMappings.value.map {
      case (_, path, arguments) ⇒ s"""-javaagent:$AppHome/${path}$arguments"""
    }
  }

  override def projectSettings = Seq(
    // don't create and/or bundle scaladoc or source code since the only artifact we will produce is a docker image
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false,
    buildOptions in docker := BuildOptions(
          cache = true,
          removeIntermediateContainers = BuildOptions.Remove.OnSuccess,
          pullBaseImage = BuildOptions.Pull.IfMissing
        ),
    cloudflowStageAppJars := Def.taskDyn {
          Def.task {
            val stagingDir  = stage.value
            val projectJars = (Runtime / internalDependencyAsJars).value.map(_.data)
            val depJars     = (Runtime / externalDependencyClasspath).value.map(_.data)

            val appJarDir = new File(stagingDir, AppJarsDir)
            val depJarDir = new File(stagingDir, DepJarsDir)
            projectJars.foreach { jar ⇒
              IO.copyFile(jar, new File(appJarDir, jar.getName))
            }
            depJars.foreach { jar ⇒
              if (jar.name.startsWith("cloudflow-runner-")) {
                IO.copyFile(jar, new File(depJarDir, "cloudflow-runner.jar"))
              } else IO.copyFile(jar, new File(depJarDir, jar.getName))
            }
          }
        }.value,
    cloudflowStageScript := Def.taskDyn {
          val log        = streams.value.log
          val javaAgents = agentJavaOptions.value
          Def.task {
            val stagingDir            = stage.value
            val runScriptTemplateURL  = getClass.getResource("/" + AppRunner)
            val runScriptTemplate     = IO.readLinesURL(runScriptTemplateURL).mkString("\n")
            val runScriptFileContents = runScriptTemplate.replace("AGENT_PLACEHOLDER", javaAgents.mkString(" "))
            val runScriptFile         = new File(new File(stagingDir, "bin"), AppRunner)

            // Optimized to make sure to only re-write the run script when the
            // contents have actually changed. This prevents unnecessary filesystem
            // changes that would result in a Docker layer being rewritten.
            if (runScriptFile.exists()) {
              // Using the same method for reading the file as we use for reading
              // the template to make sure we use the same line endings.
              val oldRunScriptFileContents = IO.readLines(runScriptFile).mkString("\n")

              if (runScriptFileContents != oldRunScriptFileContents) {
                IO.write(runScriptFile, runScriptFileContents)
                log.info(s"Successfully regenerated the streamlet runner script at ${runScriptFile}")
              } else {
                log.info(s"The streamlet runner script already exists and is up to date.")
              }
            } else {
              IO.write(runScriptFile, runScriptFileContents)
              log.info(s"Successfully generated the streamlet runner script at ${runScriptFile}")
            }
          }
        }.value,
    imageNames in docker := {
      // NOTE: only use the default repository name ("lightbend") if a registry
      //       has been set but no repository has been set.
      //
      //       When using the Openshift docker registry, the repository MUST
      //       already exist as a K8s namespace for the fallback to work.
      //       We fall back to "lightbend" because we know that Cloudflow will
      //       be installed in that namespace
      val registry  = cloudflowDockerRegistry.value
      val namespace = cloudflowDockerRepository.value.orElse(registry.map(_ ⇒ "lightbend"))

      cloudflowDockerImageName.value.map { imageName ⇒
        ImageName(
          registry = registry,
          namespace = namespace,
          repository = imageName.name,
          tag = Some(imageName.tag)
        )
      }.toSeq
    },
    dockerfile in docker := {
      // NOTE !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
      // The UID and GID of the `jboss` user is used in different parts of Cloudflow
      // If you change this, you have to make sure that all references to this value are changed
      // - fsGroups on streamlet pods uses the GID to make volumes readable
      val userInImage                  = "185" // default non-root user in the spark image
      val userAsOwner: String ⇒ String = usr ⇒ s"$usr:cloudflow"

      // this triggers side-effects, e.g. files being created in the staging area
      cloudflowStageAppJars.value
      cloudflowStageScript.value

      val appDir: File     = stage.value
      val appJarsDir: File = new File(appDir, AppJarsDir)
      val depJarsDir: File = new File(appDir, DepJarsDir)

      val executable = s"${appTargetSubdir("bin")}/$AppRunner"

      val dockerParentImage = cloudflowDockerParentImage.value

      // TODO: make it so we only build docker images when the blueprint is valid
      val applicationDescriptorLabelName = "com.lightbend.cloudflow.application.zlib"
      val applicationDescriptorLabelValue =
        applicationDescriptor.value.map { dd ⇒
          // json serialization
          val json = dd.toJson.compactPrint
          // compression
          val compressed = zlibCompression(json.getBytes(UTF_8))
          // base64 string
          Base64.getEncoder.encodeToString(compressed)
        }.get // hard get required, See TODO comment above

      // split values if required
      val labels = makeLabels(applicationDescriptorLabelName, applicationDescriptorLabelValue)

      new Dockerfile {
        from(dockerParentImage)
        user(userInImage)

        copy(new File(appDir, "prometheus"), appTargetSubdir("prometheus"), chown = userAsOwner(userInImage))
        copy(depJarsDir, optAppDir, chown = userAsOwner(userInImage))

        copy(new File(appDir, "bin"), appTargetSubdir("bin"), chown = userAsOwner(userInImage))
        runShell("chmod", "+x", executable)
        copy(appJarsDir, optAppDir, chown = userAsOwner(userInImage))
        runRaw(s"cp ${optAppDir}cloudflow-runner.jar  /opt/flink/flink-web-upload/cloudflow-runner.jar")
        run("cp", s"${AppTargetDir}/bin/${AppRunner}", "/opt")
        expose(4040) // used by the Spark UI
        labels.foreach { case (k, v) => label(k, v) }
      }
    },
    build := showResultOfBuild
          .dependsOn(
            docker.dependsOn(
              checkUncommittedChanges,
              verifyBlueprint
            )
          )
          .value,
    buildAndPublish := showResultOfBuildAndPublish
          .dependsOn(
            dockerBuildAndPush.dependsOn(
              checkUncommittedChanges,
              verifyBlueprint,
              verifyDockerRegistry
            )
          )
          .value
  )

  private val verifyDockerRegistry = Def.task {
    cloudflowDockerRegistry.value.getOrElse(throw DockerRegistryNotSet)
  }

  private val checkUncommittedChanges = Def.task {
    val log = streams.value.log
    if (cloudflowBuildNumber.value.hasUncommittedChanges) {
      log.warn(
        s"You have uncommitted changes in ${thisProjectRef.value.project}. Please commit all changes before publishing to guarantee a repeatable and traceable build."
      )
    }
  }

  private val showResultOfBuild = Def.task {
    val log         = streams.value.log
    val imagePushed = (imageNames in docker).value.head // assuming we only build a single image!

    log.info(" ") // if you remove the space, the empty line will be auto-removed by SBT somehow...
    log.info("Successfully built the following Cloudflow application image:")
    log.info(" ")
    log.info(s"  $imagePushed")
    log.info(" ")
    log.info("Before you can deploy this image to a Kubernetes cluster you will")
    log.info("need to push it to a docker registry that is reachable from the cluster.")
    log.info(" ")
  }

  private val showResultOfBuildAndPublish = Def.task {
    val log         = streams.value.log
    val imagePushed = (imageNames in docker).value.head // assuming we only build a single image!

    log.info(" ") // if you remove the space, the empty line will be auto-removed by SBT somehow...
    log.info("Successfully built and published the following Cloudflow application image:")
    log.info(" ")
    log.info(s"  $imagePushed")
    log.info(" ")
    log.info("You can deploy the application to a Kubernetes cluster using any of the the following commands:")
    log.info(" ")
    log.info(s"  kubectl cloudflow deploy $imagePushed")
    log.info(" ")
  }

  private def zlibCompression(raw: Array[Byte]): Array[Byte] = {
    val deflater   = new Deflater()
    val compressed = new ByteArrayOutputStream(0)
    deflater.setInput(raw)
    deflater.finish()
    val buffer = new Array[Byte](1024)
    while (!deflater.finished()) {
      val len = deflater.deflate(buffer)
      compressed.write(buffer, 0, len)
    }
    deflater.end()
    compressed.toByteArray()
  }

  // The label value can be > 64K - in that case we need to split into multiple labels
  private def makeLabels(labelBase: String, value: String): Map[String, String] = {

    // Value needs to be less than 64K which is the max allowed limit
    // for a single line in a Dockerfile. In case our label gets more than this size
    // we need to split it. We keep the value bound at 60K to leave some room
    val VALUE_SIZE_LIMIT_PER_LABEL = 61440 // 60K

    if (value.length() <= VALUE_SIZE_LIMIT_PER_LABEL) Map(labelBase -> value)
    else {
      val values = value.grouped(VALUE_SIZE_LIMIT_PER_LABEL).toList
      values.zipWithIndex.foldLeft(Map.empty[String, String]) {
        case (a, e) =>
          val (elem, index) = e
          if (index == 0) a + (labelBase          -> elem)
          else a + (s"$labelBase-overflow-$index" -> elem)
      }
    }
  }
}

case object DockerRegistryNotSet extends Exception(DockerRegistryNotSetError.msg) with NoStackTrace with sbt.FeedbackProvidedException
object DockerRegistryNotSetError {
  val msg =
    """
              |Please set the `cloudflowDockerRegistry` sbt setting in your build.sbt file to the registry that you want to push the image to. This Docker registry must be configured for image pulling on your target Kubernetes clusters and you should `docker login` to it before building and pushing any images.
              |Example:
              |
              |lazy val myProject = (project in file("."))
              |  .enablePlugins(CloudflowAkkaStreamsApplicationPlugin)
              |  .settings(
              |   cloudflowDockerRegistry := Some("docker-registry-default.cluster.example.com"),
              |   // other settings
              |  )
            """.stripMargin
}
