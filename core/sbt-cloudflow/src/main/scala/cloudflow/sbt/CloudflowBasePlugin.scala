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

import sbt._
import sbt.Keys._
import sbtdocker._
import sbtdocker.DockerKeys._
import com.typesafe.sbt.packager.archetypes._
import scala.util.control._

import cloudflow.sbt.CloudflowKeys._

/**
 * Base class for all Cloudflow runtime plugins for multi-image use case. Contains some
 * methods which are reused across the runtime plugins, `CloudflowFlinkPlugin`,
 * `CloudflowAkkaPlugin` and `CloudflowSparkPlugin`.
 */
object CloudflowBasePlugin extends AutoPlugin {
  final val AppHome                          = "${app_home}"
  final val AppTargetDir: String             = "/app"
  final val AppTargetSubdir: String ⇒ String = dir ⇒ s"$AppTargetDir/$dir"
  final val AppJarsDir: String               = "app-jars"
  final val DepJarsDir: String               = "dep-jars"
  final val OptAppDir                        = "/opt/cloudflow/"
  final val ScalaVersion                     = "2.12"

  // NOTE !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
  // The UID and GID of the `jboss` user is used in different parts of Cloudflow
  // If you change this, you have to make sure that all references to this value are changed
  // - fsGroups on streamlet pods uses the GID to make volumes readable
  val UserInImage                   = "185" // default non-root user in the spark image
  val userAsOwner: String ⇒ String  = usr ⇒ s"$usr:cloudflow"
  val StreamletDescriptorsLabelName = "com.lightbend.cloudflow.streamlet-descriptors"

  override def requires =
    StreamletDescriptorsPlugin && JavaAppPackaging && sbtdocker.DockerPlugin

  import ImageNameExtensions._

  override def projectSettings = Seq(
    libraryDependencies ++= Vector(
          // this artifact needs to have `%` and not `%%` as we build the runner jar
          // without version information. This is required for Flink runtime as a fixed name
          // jar needs to be uploaded to a specific location for Flink operator to pick up
          "com.lightbend.cloudflow" % "cloudflow-runner"       % (ThisProject / cloudflowVersion).value,
          "com.lightbend.cloudflow" %% "cloudflow-localrunner" % (ThisProject / cloudflowVersion).value
        ),
    buildOptions in docker := BuildOptions(
          cache = true,
          removeIntermediateContainers = BuildOptions.Remove.OnSuccess,
          pullBaseImage = BuildOptions.Pull.IfMissing
        ),
    imageNames in docker := {
      val registry  = cloudflowDockerRegistry.value
      val namespace = cloudflowDockerRepository.value

      cloudflowDockerImageName.value.map { imageName ⇒
        sbtdocker.ImageName(
          registry = registry,
          namespace = namespace,
          repository = imageName.name,
          tag = Some(imageName.tag)
        )
      }.toSeq
    },
    build := showResultOfBuild
          .dependsOn(
            docker.dependsOn(
              streamletDescriptorsInProject
            )
          )
          .value,
    buildAndPublish := Def.task {
          val log = streams.value.log
          log.err("""`buildAndPublish` is deprecated since Cloudflow v2.0. Use `buildApp` instead.
                     | See https://cloudflow.io/docs/current/project-info/migration-1_3-2_0.html#_build_process for more info.
                  """.stripMargin)
        }.value,
    buildAndPublishImage := Def.taskDyn {
          def buildAndPublishLog(log: sbt.internal.util.ManagedLogger)(imageName: ImageName, imageDigest: ImageDigest) = {
            log.info(" ") // if you remove the space, the empty line will be auto-removed by SBT somehow...
            log.info("Successfully built and published the following image:")
            log.info(imageName.referenceWithDigest(imageDigest))
          }

          if (cloudflowDockerRegistry.value.isEmpty) Def.task {
            val streamletDescriptors = streamletDescriptorsInProject.value

            val _           = docker.value
            val dockerImage = verifyDockerImage.value
            val returnImageName = ImageName(
              registry = dockerImage.registry,
              namespace = dockerImage.namespace,
              repository = dockerImage.repository,
              tag = dockerImage.tag
            )

            val log          = streams.value.log
            val imageVersion = (ThisProject / version).value
            val imageDigest  = ImageDigest("", imageVersion, includeAlgorithm = false)

            buildAndPublishLog(log)(returnImageName, imageDigest)

            log.warn("""*** WARNING ***""")
            log.warn("""You haven't specified the "cloudflowDockerRegistry" in your build.sbt""")
            log.warn("""To have a working deployment you should make the produced docker image available """)
            log.warn("""in a docker registry accessible from your cluster nodes""")
            log.warn(s"""The Cloudflow application CR points to ${dockerImage}${imageVersion}""")

            ImageNameAndDigest(returnImageName, imageDigest) -> streamletDescriptors
          } else
            Def.task {
              val streamletDescriptors = streamletDescriptorsInProject.value
              val imageNameToDigest: Map[ImageName, ImageDigest] =
                dockerBuildAndPush.value.map {
                  case (k, v) => ImageName(k.registry, k.namespace, k.repository, k.tag) -> ImageDigest(v.algorithm, v.digest)
                }
              if (imageNameToDigest.size > 1) throw TooManyImagesBuilt
              val (imageName, imageDigest) = imageNameToDigest.head

              buildAndPublishLog(streams.value.log)(imageName, imageDigest)
              ImageNameAndDigest(imageName, imageDigest) -> streamletDescriptors
            }
        }.value,
    fork in Compile := true,
    extraDockerInstructions := Seq(),
    ownerInDockerImage := userAsOwner(UserInImage)
  )

  private[sbt] val verifyDockerImage = Def.task {
    (imageNames in docker).value.headOption.getOrElse(throw DockerRegistryNotSet)
  }

  private[sbt] val showResultOfBuild = Def.task {
    val log         = streams.value.log
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

case object DockerRegistryNotSet extends Exception(DockerRegistryNotSetError.msg) with NoStackTrace with sbt.FeedbackProvidedException
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

case object TooManyImagesBuilt extends Exception(TooManyImagesBuiltError.msg) with NoStackTrace with sbt.FeedbackProvidedException
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
final case class ImageNameAndDigest(imageName: ImageName, imageId: ImageDigest)
