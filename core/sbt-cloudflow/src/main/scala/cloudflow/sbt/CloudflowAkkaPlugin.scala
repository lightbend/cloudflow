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
import CloudflowBasePlugin._
import com.lightbend.sbt.javaagent.JavaAgent
import com.lightbend.sbt.javaagent.JavaAgent.JavaAgentKeys.resolvedJavaAgents
import java.io.File

object CloudflowAkkaPlugin extends AutoPlugin {
  val AppRunner: String = "akka-entrypoint.sh"
  val AppHome           = "${app_home}"

  override def requires = CloudflowBasePlugin && JavaAgent

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
    libraryDependencies ++= Vector(
          "com.lightbend.cloudflow" %% "cloudflow-akka-util"    % BuildInfo.version,
          "com.lightbend.cloudflow" %% "cloudflow-akka"         % BuildInfo.version,
          "com.lightbend.cloudflow" %% "cloudflow-akka-testkit" % BuildInfo.version % "test"
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
              IO.copyFile(jar, new File(depJarDir, jar.getName))
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
    dockerfile in docker := {
      // this triggers side-effects, e.g. files being created in the staging area
      cloudflowStageAppJars.value
      cloudflowStageScript.value

      val appDir: File     = stage.value
      val appJarsDir: File = new File(appDir, AppJarsDir)
      val depJarsDir: File = new File(appDir, DepJarsDir)
      val executable       = s"${AppTargetSubdir("bin")}/$AppRunner"

      // pack all streamlet-descriptors into a Json array
      val streamletDescriptorsJson = streamletDescriptorsInProject.value.toJson

      val streamletDescriptorsLabelValue = makeStreamletDescriptorsLabelValue(streamletDescriptorsJson)

      new Dockerfile {
        from("adoptopenjdk/openjdk8")
        runRaw(
          "groupadd -r cloudflow -g 185 && useradd -u 185 -r -g root -G cloudflow -m -d /home/cloudflow -s /sbin/nologin -c CloudflowUser cloudflow"
        )
        user(UserInImage)
        copy(new File(appDir, "bin"), AppTargetSubdir("bin"), chown = userAsOwner(UserInImage))
        runShell("chmod", "+x", executable)

        copy(depJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
        copy(appJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
        user("root")

        run("cp", s"${AppTargetDir}/bin/${AppRunner}", "/opt")
        runShell("chown", "cloudflow", s"/opt/$AppRunner")
        runShell("chgrp", "cloudflow", s"/opt/$AppRunner")
        user(UserInImage)

        label(StreamletDescriptorsLabelName, streamletDescriptorsLabelValue)
        entryPoint(s"/opt/$AppRunner")
      }
    }
  )
}
