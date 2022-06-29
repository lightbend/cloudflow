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
import sbt.Keys._
import sbt._

/**
 * SBT Plugin that centralizes the use of common keys for Cloudflow projects.
 */
object CommonSettingsAndTasksPlugin extends AutoPlugin {

  /** Make public keys available. */
  object autoImport extends CloudflowKeys

  import autoImport._

  /** Set default values for keys. */
  override def projectSettings =
    Seq(
      cloudflowVersion := Cloudflow.Version,
      cloudflowDockerImageName := Def.task {
          Some(DockerImageName((ThisProject / name).value.toLowerCase, (ThisProject / version).value))
        }.value,
      cloudflowWorkDir := (ThisBuild / baseDirectory).value / "target" / ".cloudflow",
      imageNamesByProject := Def.taskDyn {
          val buildNumber = (ThisProject / version).value
          Def.task {
            buildStructure.value.allProjectRefs
              .map(_.project)
              .foldLeft(Map.empty[String, DockerImageName]) { (a, e) =>
                a + (e.toLowerCase -> DockerImageName(e.toLowerCase, buildNumber))
              }
          }
        }.value,
      Compile / packageDoc / publishArtifact := false,
      Compile / packageSrc / publishArtifact := false)
}

trait CloudflowKeys extends CloudflowSettingKeys with CloudflowTaskKeys {
  object Cloudflow {
    final val Version = BuildInfo.version
    object library {
      final val CloudflowAvro = "com.lightbend.cloudflow" %% "cloudflow-avro" % Version
      final val CloudflowProto = "com.lightbend.cloudflow" %% "cloudflow-proto" % Version
    }
  }
}

object CloudflowKeys extends CloudflowKeys
