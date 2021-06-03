/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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
import scala.util.Try

import sbt._
import sbt.Keys._
import sbtdocker._
import sbtdocker.DockerKeys._
import com.typesafe.sbt.packager.archetypes._
import scala.util.control._

import cloudflow.sbt.CloudflowKeys._
import cloudflow.blueprint.StreamletDescriptor

/**
 * Base class for all Cloudflow runtime plugins for multi-image use case. Contains some
 * methods which are reused across the runtime plugins, `CloudflowFlinkPlugin`,
 * `CloudflowAkkaPlugin` and `CloudflowSparkPlugin`.
 */
object CloudflowBasePlugin extends AutoPlugin {
  final val AppHome = "${app_home}"
  final val AppTargetDir: String = "/app"
  final val AppTargetSubdir: String => String = dir => s"$AppTargetDir/$dir"
  final val AppJarsDir: String = "app-jars"
  final val DepJarsDir: String = "dep-jars"
  final val OptAppDir = "/opt/cloudflow/"
  final val ScalaVersion = "2.12"

  // NOTE !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
  // The UID and GID of the `jboss` user is used in different parts of Cloudflow
  // If you change this, you have to make sure that all references to this value are changed
  // - fsGroups on streamlet pods uses the GID to make volumes readable
  val UserInImage = "185" // default non-root user in the spark image
  val userAsOwner: String => String = usr => s"$usr:cloudflow"
  val StreamletDescriptorsLabelName = "com.lightbend.cloudflow.streamlet-descriptors"

  override def requires =
    StreamletDescriptorsPlugin && JavaAppPackaging && sbtdocker.DockerPlugin

  override def projectSettings =
    Seq(
      cloudflowDockerBaseImage := "adoptopenjdk/openjdk8:alpine",
      libraryDependencies ++= Vector(
          "com.lightbend.cloudflow" % s"cloudflow-runner_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value,
          "com.lightbend.cloudflow" % s"cloudflow-localrunner_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value),
      buildOptions in docker := BuildOptions(
          cache = true,
          removeIntermediateContainers = BuildOptions.Remove.OnSuccess,
          pullBaseImage = BuildOptions.Pull.IfMissing),
      imageNames in docker := {
        val registry = cloudflowDockerRegistry.value
        val namespace = cloudflowDockerRepository.value

        cloudflowDockerImageName.value.map { imageName =>
          sbtdocker.ImageName(
            registry = registry,
            namespace = namespace,
            repository = imageName.name,
            tag = Some(imageName.tag))
        }.toSeq
      },
      build := showResultOfBuild
          .dependsOn(docker.dependsOn(streamletDescriptorsInProject))
          .value,
      buildAndPublish := Def.task {
          val log = streams.value.log
          log.err(
            """`buildAndPublish` is deprecated since Cloudflow v2.0. Use `buildApp` instead.
                     | See https://cloudflow.io/docs/current/project-info/migration-1_3-2_0.html#_build_process for more info.
                  """.stripMargin)
        }.value,
      buildAndPublishImage := Def.taskDyn {
          def buildAndPublishLog(log: sbt.internal.util.ManagedLogger)(imageRef: ImageRef) = {
            log.info(" ") // if you remove the space, the empty line will be auto-removed by SBT somehow...
            log.info("Successfully built and published the following image:")
            log.info(imageRef.fullReference)
          }

          val streamletDescriptors = streamletDescriptorsInProject.value.fold(e => throw e, identity)
          if (cloudflowDockerRegistry.value.isEmpty) Def.task {
            val _ = docker.value
            val dockerImage = verifyDockerImage.value

            val log = streams.value.log
            val imageRef = BasicImageRef(dockerImage)

            buildAndPublishLog(log)(imageRef)

            log.warn("""*** WARNING ***""")
            log.warn("""You haven't specified the "cloudflowDockerRegistry" in your build.sbt""")
            log.warn("""To have a working deployment you should make the produced docker image available """)
            log.warn("""in a docker registry accessible from your cluster nodes""")
            log.warn(s"""The Cloudflow application CR points to ${dockerImage}""")

            (imageRef -> streamletDescriptors): (ImageRef, Map[String, StreamletDescriptor])
          }
          else
            Def.task {
              val imageNameToDigest: Map[ImageName, ImageDigest] =
                dockerBuildAndPush.value.map {
                  case (k, v) =>
                    ImageName(k.registry, k.namespace, k.repository, k.tag) -> ImageDigest(v.algorithm, v.digest)
                }
              if (imageNameToDigest.size > 1) throw TooManyImagesBuilt
              val (imageName, imageDigest) = imageNameToDigest.head
              val imageRef = ShaImageRef(imageName, imageDigest)

              buildAndPublishLog(streams.value.log)(imageRef)
              (imageRef -> streamletDescriptors): (ImageRef, Map[String, StreamletDescriptor])
            }
        }.value,
      fork in Compile := true,
      extraDockerInstructions := Seq(),
      ownerInDockerImage := userAsOwner(UserInImage))

  private[sbt] val verifyDockerImage = Def.task {
    (imageNames in docker).value.headOption.getOrElse(throw DockerRegistryNotSet)
  }

  private[sbt] val showResultOfBuild = Def.task {
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
}

case object DockerImageNotSet extends Exception("Cannot indentify the docker image to build.") with NoStackTrace

case object DockerRegistryNotSet
    extends Exception(DockerRegistryNotSetError.msg)
    with NoStackTrace
    with sbt.FeedbackProvidedException
object DockerRegistryNotSetError {
  val msg =
    """
      |Please set the `cloudflowDockerRegistry` sbt setting in your build.sbt file to the registry that you want to push the image to.
      |This Docker registry must be configured for image pulling on your target Kubernetes clusters.
      |You must authenticate to your target Docker registry using `docker login` to it before building and pushing any images.
      |
      |Example:
      |
      |lazy val myProject = (project in file("."))
      |  .enablePlugins(CloudflowAkkaPlugin)
      |  .settings(
      |   cloudflowDockerRegistry := Some("docker-registry-default.cluster.example.com"),
      |   // other settings
      |  )
    """.stripMargin
}

case object TooManyImagesBuilt
    extends Exception(TooManyImagesBuiltError.msg)
    with NoStackTrace
    with sbt.FeedbackProvidedException
object TooManyImagesBuiltError {
  val msg =
    """
      | Unexpected error, the project built more than one docker image.
    """.stripMargin
}

final case class ImageDigest(val algorithm: String, val digest: String, includeAlgorithm: Boolean = true) {
  override def toString =
    if (includeAlgorithm) {
      s"$algorithm:$digest"
    } else {
      digest
    }
}
sealed trait ImageRef {
  val fullReference: String
}
final case class ShaImageRef(imageName: ImageName, imageId: ImageDigest) extends ImageRef {
  val fullReference = {
    val registryString = imageName.registry.fold("")(_ + "/")
    val namespaceString = imageName.namespace.fold("")(_ + "/")
    val imageNameStr = registryString + namespaceString + imageName.repository
    s"$imageNameStr@$imageId"
  }
}
final case class BasicImageRef(imageName: ImageName) extends ImageRef {
  val fullReference = imageName.toString
}
