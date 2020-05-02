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
import cloudflow.sbt.CloudflowKeys._
import CloudflowBasePlugin._

object CloudflowFlinkPlugin extends AutoPlugin {
  final val FlinkVersion = "1.10.0"
  final val CloudflowFlinkDockerBaseImage =
    s"lightbend/flink:${CloudflowBasePlugin.CloudflowVersion}-cloudflow-flink-$FlinkVersion-scala-${CloudflowBasePlugin.ScalaVersion}"

  override def requires = CloudflowBasePlugin

  override def projectSettings = Seq(
    libraryDependencies ++= Vector(
          "com.lightbend.cloudflow" %% "cloudflow-flink"         % BuildInfo.version,
          "com.lightbend.cloudflow" %% "cloudflow-flink-testkit" % BuildInfo.version % "test"
        ),
    cloudflowDockerParentImage := CloudflowFlinkDockerBaseImage,
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
    dockerfile in docker := {
      // this triggers side-effects, e.g. files being created in the staging area
      cloudflowStageAppJars.value

      val appDir: File     = stage.value
      val appJarsDir: File = new File(appDir, AppJarsDir)
      val depJarsDir: File = new File(appDir, DepJarsDir)
      new Dockerfile {
        from(CloudflowFlinkDockerBaseImage)
        user(UserInImage)

        copy(depJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
        copy(appJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
        addInstructions(extraDockerInstructions.value)
        runRaw(s"cp ${OptAppDir}cloudflow-runner.jar  /opt/flink/flink-web-upload/cloudflow-runner.jar")
      }
    }
  )
}
