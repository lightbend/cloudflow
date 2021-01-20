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

import sbt._
import sbt.Keys._
import sbtdocker._
import sbtdocker.DockerKeys._
import sbtdocker.Instructions
import sbtdocker.staging.CopyFile
import com.typesafe.sbt.packager.Keys._
import cloudflow.sbt.CloudflowKeys._
import CloudflowBasePlugin._

object CloudflowFlinkPlugin extends AutoPlugin {

  override def requires = CloudflowBasePlugin

  override def projectSettings = Seq(
    cloudflowFlinkBaseImage := None,
    libraryDependencies ++= Vector(
          "com.lightbend.cloudflow" % s"cloudflow-flink_${(ThisProject / scalaBinaryVersion).value}"         % (ThisProject / cloudflowVersion).value,
          "com.lightbend.cloudflow" % s"cloudflow-flink-testkit_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value % "test"
        ),
    cloudflowStageAppJars := Def.taskDyn {
          Def.task {
            val stagingDir  = stage.value
            val projectJars = (Runtime / internalDependencyAsJars).value.map(_.data)
            val depJars     = (Runtime / externalDependencyClasspath).value.map(_.data)

            val appJarDir = new File(stagingDir, AppJarsDir)
            val depJarDir = new File(stagingDir, DepJarsDir)

            IO.delete(appJarDir)
            IO.delete(depJarDir)

            projectJars.foreach { jar =>
              IO.copyFile(jar, new File(appJarDir, jar.getName))
            }
            depJars.foreach { jar =>
              IO.copyFile(jar, new File(depJarDir, jar.getName))
            }
          }
        }.value,
    baseDockerInstructions := {
      val appDir: File     = stage.value
      val appJarsDir: File = new File(appDir, AppJarsDir)
      val depJarsDir: File = new File(appDir, DepJarsDir)

      val configSh = (ThisProject / target).value / "cloudflow" / "flink" / "config.sh"
      IO.write(configSh, configShContent)

      val flinkConsole = (ThisProject / target).value / "cloudflow" / "flink" / "flink-console.sh"
      IO.write(flinkConsole, flinkConsoleContent)

      val flinkEntrypoint = (ThisProject / target).value / "cloudflow" / "flink" / "flink-entrypoint.sh"
      IO.write(flinkEntrypoint, flinkEntrypointContent)

      val scalaVersion = (ThisProject / scalaBinaryVersion).value
      val flinkVersion = "1.10.0"
      val flinkHome    = "/opt/flink"

      val flinkTgz    = s"lightbend-flink-${flinkVersion}.tgz"
      val flinkTgzUrl = s"https://github.com/lightbend/flink/releases/download/v$flinkVersion-lightbend/$flinkTgz"

      Seq(
        Instructions.Env("FLINK_VERSION", flinkVersion),
        Instructions.Env("SCALA_VERSION", scalaVersion),
        Instructions.Env("FLINK_HOME", flinkHome),
        Instructions.Env("FLINK_ENV_JAVA_OPTS", "-Dlogback.configurationFile=/opt/logging/logback.xml"),
        Instructions.User("root"),
        Instructions.Copy(CopyFile(flinkEntrypoint), "/opt/flink-entrypoint.sh"),
        Instructions.Copy(CopyFile(configSh), "/tmp/config.sh"),
        Instructions.Copy(CopyFile(flinkConsole), "/tmp/flink-console.sh"),
        Instructions.Run.shell(
          Seq(
            Seq("apk", "add", "curl", "wget", "bash", "snappy-dev", "gettext-dev"),
            Seq("wget", flinkTgzUrl),
            Seq("tar", "-xvzf", flinkTgz),
            Seq("mv", s"flink-${flinkVersion}", flinkHome),
            Seq("rm", flinkTgz),
            Seq("cp", "/tmp/config.sh", s"${flinkHome}/bin/config.sh"),
            Seq("cp", "/tmp/flink-console.sh", s"${flinkHome}/bin/flink-console.sh"),
            // logback configuration:
            // https://ci.apache.org/projects/flink/flink-docs-stable/deployment/advanced/logging.html#configuring-logback
            // logback must be provided by the streamlet itself
            Seq("rm", s"${flinkHome}/lib/log4j-slf4j-impl-2.12.1.jar"),
            Seq("addgroup", "-S", "-g", "9999", "flink"),
            Seq("adduser", "-S", "-h", flinkHome, "-u", "9999", "flink", "flink"),
            Seq("addgroup", "-S", "-g", "185", "cloudflow"),
            Seq("adduser", "-u", "185", "-S", "-h", "/home/cloudflow", "-s", "/sbin/nologin", "cloudflow", "root"),
            Seq("adduser", "cloudflow", "cloudflow"),
            Seq("rm", "-rf", "/var/lib/apt/lists/*"),
            Seq("chown", "-R", "flink:flink", "/var"),
            Seq("chown", "-R", "flink:root", "/usr/local"),
            Seq("chmod", "775", "/usr/local"),
            Seq("mkdir", s"${flinkHome}/flink-web-upload"),
            Seq("mv", s"${flinkHome}/opt/flink-queryable-state-runtime_${scalaVersion}-${flinkVersion}.jar", s"${flinkHome}/lib"),
            Seq("mkdir", "-p", "/prometheus"),
            Seq(
              "curl",
              "https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.11.0/jmx_prometheus_javaagent-0.11.0.jar",
              "-o",
              "/prometheus/jmx_prometheus_javaagent.jar"
            ),
            Seq("chmod", "-R", "777", flinkHome),
            Seq("chmod", "a+x", s"${flinkHome}/bin/config.sh"),
            Seq("chmod", "777", s"${flinkHome}/bin/flink-console.sh"),
            Seq("chown", "501:dialout", s"${flinkHome}/bin/flink-console.sh"),
            Seq("chmod", "a+x", "/opt/flink-entrypoint.sh")
          ).reduce(_ ++ Seq("&&") ++ _)
        ),
        Instructions.EntryPoint.exec(Seq("bash", "/opt/flink-entrypoint.sh")),
        Instructions.User(UserInImage),
        Instructions.Copy(sources = Seq(CopyFile(depJarsDir)), destination = OptAppDir, chown = Some(userAsOwner(UserInImage))),
        Instructions.Copy(sources = Seq(CopyFile(appJarsDir)), destination = OptAppDir, chown = Some(userAsOwner(UserInImage))),
        Instructions.Run(
          s"cp ${OptAppDir}cloudflow-runner_${(ThisProject / scalaBinaryVersion).value}*.jar  /opt/flink/flink-web-upload/cloudflow-runner.jar"
        )
      )
    },
    dockerfile in docker := {
      val log = streams.value.log

      IO.delete(((ThisProject / target).value / "docker"))

      // this triggers side-effects, e.g. files being created in the staging area
      cloudflowStageAppJars.value

      cloudflowFlinkBaseImage.value match {
        case Some(baseImage) =>
          log.warn("'cloudflowFlinkBaseImage' is defined, 'cloudflowDockerBaseImage' setting is going to be ignored")

          val appDir: File     = stage.value
          val appJarsDir: File = new File(appDir, AppJarsDir)
          val depJarsDir: File = new File(appDir, DepJarsDir)

          new Dockerfile {
            from(baseImage)
            user(UserInImage)

            copy(depJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
            copy(appJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
            addInstructions(extraDockerInstructions.value)
            runRaw(
              s"cp ${OptAppDir}cloudflow-runner_${(ThisProject / scalaBinaryVersion).value}*.jar  /opt/flink/flink-web-upload/cloudflow-runner.jar"
            )
          }
        case _ =>
          new Dockerfile {
            from(cloudflowDockerBaseImage.value)
            addInstructions((ThisProject / baseDockerInstructions).value)
            addInstructions((ThisProject / extraDockerInstructions).value)
          }
      }
    }
  )

  private lazy val configShContent =
    scala.io.Source.fromResource("runtimes/flink/config.sh", getClass().getClassLoader()).getLines.mkString("\n")

  private lazy val flinkConsoleContent =
    scala.io.Source.fromResource("runtimes/flink/flink-console.sh", getClass().getClassLoader()).getLines.mkString("\n")

  private lazy val flinkEntrypointContent =
    scala.io.Source.fromResource("runtimes/flink/flink-entrypoint.sh", getClass().getClassLoader()).getLines.mkString("\n")

}
