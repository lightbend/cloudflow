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
import com.typesafe.sbt.packager.Keys._
import spray.json._
import cloudflow.sbt.CloudflowKeys._
import cloudflow.blueprint.StreamletDescriptorFormat._
import cloudflow.sbt.CloudflowKeys._

object CloudflowFlinkPlugin extends CloudflowBasePlugin {
  final val flinkVersion                  = "1.9.2"
  final val CloudflowFlinkDockerBaseImage = s"lightbend/flink:cloudflow-flink-$flinkVersion-scala-$scalaVersion"

  override def projectSettings = Seq(
    libraryDependencies ++= Vector(
          "com.lightbend.cloudflow" % "cloudflow-runner"         % BuildInfo.version,
          "com.lightbend.cloudflow" %% "cloudflow-flink"         % BuildInfo.version,
          "com.lightbend.cloudflow" %% "cloudflow-flink-testkit" % BuildInfo.version % "test"
        ),
    cloudflowDockerParentImage := CloudflowFlinkDockerBaseImage,
    cloudflowFlinkDockerImageName := Def.task {
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
    imageNames in docker := {
      val registry  = cloudflowDockerRegistry.value
      val namespace = cloudflowDockerRepository.value

      cloudflowFlinkDockerImageName.value.map { imageName ⇒
        ImageName(
          registry = registry,
          namespace = namespace,
          repository = imageName.name,
          tag = Some(imageName.tag)
        )
      }.toSeq
    },
    dockerfile in docker := {
      // this triggers side-effects, e.g. files being created in the staging area
      cloudflowStageAppJars.value

      val appDir: File     = stage.value
      val appJarsDir: File = new File(appDir, AppJarsDir)
      val depJarsDir: File = new File(appDir, DepJarsDir)

      val streamletDescriptorsLabelName = "com.lightbend.cloudflow.streamlet-descriptors"
      // pack all streamlet-descriptors into a Json array
      val streamletDescriptorsJson =
        streamletDescriptorsInProject.value.toJson

      val streamletDescriptorsLabelValue = makeStreamletDescriptorsLabelValue(streamletDescriptorsJson)

      new Dockerfile {
        from(CloudflowFlinkDockerBaseImage)
        user(UserInImage)

        copy(depJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
        copy(appJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
        runRaw(s"cp ${OptAppDir}cloudflow-runner.jar  /opt/flink/flink-web-upload/cloudflow-runner.jar")
        label(streamletDescriptorsLabelName, streamletDescriptorsLabelValue)
      }
    },
    build := showResultOfBuild
          .dependsOn(
            docker.dependsOn(
              checkUncommittedChanges
            )
          )
          .value,
    buildAndPublish := showResultOfBuildAndPublish
          .dependsOn(
            dockerBuildAndPush.dependsOn(
              checkUncommittedChanges,
              verifyDockerRegistry
            )
          )
          .value,
    fork in Compile := true
  )
}
