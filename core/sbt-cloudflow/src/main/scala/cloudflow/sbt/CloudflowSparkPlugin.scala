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

import com.typesafe.config._
import sbt._
import sbt.Keys._
import sbtdocker._
import sbtdocker.DockerKeys._
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.archetypes._
import spray.json._

import cloudflow.blueprint.StreamletDescriptorFormat._
import cloudflow.sbt.CloudflowKeys._
import cloudflow.blueprint.StreamletDescriptor

object CloudflowSparkPlugin extends AutoPlugin {
  val AppHome = "${app_home}"
  val AppTargetDir: String = "/app"
  val appTargetSubdir: String ⇒ String = dir ⇒ s"$AppTargetDir/$dir"
  val AppJarsDir: String = "app-jars"
  val DepJarsDir: String = "dep-jars"
  val optAppDir = "/opt/cloudflow/"
  final val sparkVersion = "2.4.4"
  final val scalaVersion = "2.12"
  final val cloudflowVersion = "1.3.1-SNAPSHOT"
  final val CloudflowSparkDockerBaseImage = s"lightbend/spark:$cloudflowVersion-cloudflow-spark-$sparkVersion-scala-$scalaVersion"

  override def requires = CommonSettingsAndTasksPlugin && StreamletScannerPlugin &&
    JavaAppPackaging && sbtdocker.DockerPlugin

  override def projectSettings = Seq(
    libraryDependencies ++= Vector(
      "com.lightbend.cloudflow" % "cloudflow-runner" % BuildInfo.version,
      "com.lightbend.cloudflow" %% "cloudflow-spark" % BuildInfo.version,
      "com.lightbend.cloudflow" %% "cloudflow-spark-testkit" % BuildInfo.version % "test"
    ),
    cloudflowDockerParentImage := CloudflowSparkDockerBaseImage,
    cloudflowSparkDockerImageName := Def.task {
      Some(DockerImageName((ThisProject / name).value.toLowerCase, (ThisProject / cloudflowBuildNumber).value.buildNumber))
    }.value,

    streamletDescriptorsInProject := Def.taskDyn {
      val detectedStreamlets = cloudflowStreamletDescriptors.value
      buildStreamletDescriptors(detectedStreamlets)
    }.value,

    buildOptions in docker := BuildOptions(
      cache = true,
      removeIntermediateContainers = BuildOptions.Remove.OnSuccess,
      pullBaseImage = BuildOptions.Pull.IfMissing
    ),

    cloudflowStageAppJars := Def.taskDyn {
      Def.task {
        val stagingDir = stage.value
        val projectJars = (Runtime / internalDependencyAsJars).value.map(_.data)
        val depJars = (Runtime / externalDependencyClasspath).value.map(_.data)

        val appJarDir = new File(stagingDir, AppJarsDir)
        val depJarDir = new File(stagingDir, DepJarsDir)
        projectJars.foreach { jar ⇒
          IO.copyFile(jar, new File(appJarDir, jar.getName))
        }
        depJars.foreach { jar ⇒
          IO.copyFile(jar, new File(depJarDir, jar.getName))
        }
      }
    }.value,

    imageNames in docker := {
      val registry = cloudflowDockerRegistry.value
      val namespace = cloudflowDockerRepository.value.orElse(registry.map(_ ⇒ "lightbend"))

      cloudflowSparkDockerImageName.value
        .map { imageName ⇒
          ImageName(
            registry = registry,
            namespace = namespace,
            repository = imageName.name,
            tag = Some(imageName.tag)
          )
        }
        .toSeq
    },

    dockerfile in docker := {
      // NOTE !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
      // The UID and GID of the `jboss` user is used in different parts of Cloudflow
      // If you change this, you have to make sure that all references to this value are changed
      // - fsGroups on streamlet pods uses the GID to make volumes readable
      val userInImage = "185" // default non-root user in the spark image
      val userAsOwner: String ⇒ String = usr ⇒ s"$usr:cloudflow"

      // this triggers side-effects, e.g. files being created in the staging area
      cloudflowStageAppJars.value

      val appDir: File = stage.value
      val appJarsDir: File = new File(appDir, AppJarsDir)
      val depJarsDir: File = new File(appDir, DepJarsDir)

      val streamletDescriptorsLabelName = "com.lightbend.cloudflow.streamlet-descriptors"
      // pack all streamlet-descriptors into a Json array
      val streamletDescriptorsJson =
        streamletDescriptorsInProject
          .value
          .toJson

      // create a root object with the array
      val streamletDescriptorsJsonStr =
        JsObject("descriptors" -> streamletDescriptorsJson)
          .compactPrint

      val compressed = zlibCompression(streamletDescriptorsJsonStr.getBytes(UTF_8))
      val streamletDescriptorsLabelValue =
        Base64.getEncoder.encodeToString(compressed)

      new Dockerfile {
        from(CloudflowSparkDockerBaseImage)
        user(userInImage)
        copy(depJarsDir, optAppDir, chown = userAsOwner(userInImage))
        copy(appJarsDir, optAppDir, chown = userAsOwner(userInImage))
        expose(4040)
        label(streamletDescriptorsLabelName, streamletDescriptorsLabelValue)
      }
    },

    build := showResultOfBuild.dependsOn(
      docker.dependsOn(
        checkUncommittedChanges
      )
    ).value,

    buildAndPublish := showResultOfBuildAndPublish.dependsOn(
      dockerBuildAndPush.dependsOn(
        checkUncommittedChanges,
        verifyDockerRegistry
      )
    ).value,

    fork in Compile := true
  )

  private val verifyDockerRegistry = Def.task {
    cloudflowDockerRegistry.value.getOrElse(throw DockerRegistryNotSet)
  }

  private val checkUncommittedChanges = Def.task {
    val log = streams.value.log
    if (cloudflowBuildNumber.value.hasUncommittedChanges) {
      log.warn(s"You have uncommitted changes in ${thisProjectRef.value.project}. Please commit all changes before publishing to guarantee a repeatable and traceable build.")
    }
  }

  private val showResultOfBuild = Def.task {
    val log = streams.value.log
    val imagePushed = (imageNames in docker).value.head // assuming we only build a single image!

    log.info(" ") // if you remove the space, the empty line will be auto-removed by SBT somehow...
    log.info("Successfully built the following image:")
    log.info(" ")
    log.info(s"  $imagePushed")
    log.info(" ")
    log.info("Before you can deploy this image to a Kubernetes cluster you will")
    log.info("need to push it to a docker registry that is reachable from the cluster.")
    log.info(" ")
  }

  private val showResultOfBuildAndPublish = Def.task {
    val log = streams.value.log
    val imagePushed = (imageNames in docker).value.head // assuming we only build a single image!

    log.info(" ") // if you remove the space, the empty line will be auto-removed by SBT somehow...
    log.info("Successfully built and published the following image:")
    log.info(s"  $imagePushed")
  }

  def zlibCompression(raw: Array[Byte]): Array[Byte] = {
    val deflater = new Deflater()
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

  def buildStreamletDescriptors(
      detectedStreamlets: Map[String, Config]): Def.Initialize[Task[Iterable[StreamletDescriptor]]] = Def.task {
    val detectedStreamletDescriptors = detectedStreamlets.map {
      case (_, configDescriptor) ⇒
        val jsonString = configDescriptor.root().render(ConfigRenderOptions.concise())
        jsonString.parseJson.convertTo[cloudflow.blueprint.StreamletDescriptor]
    }
    detectedStreamletDescriptors
  }
}
