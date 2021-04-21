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
import java.io.File

object CloudflowAkkaPlugin extends AutoPlugin {

  override def requires = CloudflowBasePlugin

  override def projectSettings =
    Seq(
      cloudflowAkkaBaseImage := None,
      libraryDependencies ++= Vector(
          "com.lightbend.cloudflow" % s"cloudflow-akka-util_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value,
          "com.lightbend.cloudflow" % s"cloudflow-akka_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value,
          "com.lightbend.cloudflow" % s"cloudflow-akka-testkit_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value % "test"),
      cloudflowStageAppJars := Def.taskDyn {
          Def.task {
            val stagingDir = stage.value
            val projectJars = (Runtime / internalDependencyAsJars).value.map(_.data)
            val depJars = (Runtime / externalDependencyClasspath).value.map(_.data)

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
        val appDir: File = stage.value
        val appJarsDir: File = new File(appDir, AppJarsDir)
        val depJarsDir: File = new File(appDir, DepJarsDir)

        val akkaEntrypointFile = (ThisProject / target).value / "cloudflow" / "akka" / "akka-entrypoint.sh"
        IO.write(akkaEntrypointFile, akkaEntrypointContent)

        val prometheusYaml = (ThisProject / target).value / "cloudflow" / "akka" / "prometheus.yaml"
        IO.write(prometheusYaml, prometheusYamlContent)

        Seq(
          Instructions.User("root"),
          // logback configuration
          // https://doc.akka.io/docs/akka/current/logging.html#slf4j
          Instructions.Env(
            "LOGBACK_CONFIG",
            "-Dlogback.configurationFile=/opt/logging/logback.xml -Dakka.loggers.0=akka.event.slf4j.Slf4jLogger -Dakka.loglevel=DEBUG -Dakka.logging-filter=akka.event.slf4j.Slf4jLoggingFilter"),
          Instructions.Copy(CopyFile(akkaEntrypointFile), "/opt/akka-entrypoint.sh"),
          Instructions.Copy(CopyFile(prometheusYaml), "/etc/metrics/conf/prometheus.yaml"),
          Instructions.Run.shell(
            Seq(
              Seq("apk", "add", "bash", "curl"),
              Seq("mkdir", "-p", "/home/cloudflow"),
              Seq("mkdir", "-p", "/opt"),
              Seq("addgroup", "-g", "185", "-S", "cloudflow"),
              Seq(
                "adduser",
                "-u",
                "185",
                "-S",
                "-h",
                "/home/cloudflow",
                "-s",
                "/sbin/nologin",
                "cloudflow",
                "cloudflow"),
              Seq("adduser", "cloudflow", "root"),
              Seq("mkdir", "-p", "/prometheus"),
              Seq(
                "curl",
                "https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.11.0/jmx_prometheus_javaagent-0.11.0.jar",
                "-o",
                "/prometheus/jmx_prometheus_javaagent.jar"),
              Seq("chmod", "a+x", "/opt/akka-entrypoint.sh")).reduce(_ ++ Seq("&&") ++ _)),
          Instructions.User(UserInImage),
          Instructions.EntryPoint.exec(Seq("bash", "/opt/akka-entrypoint.sh")),
          Instructions
            .Copy(sources = Seq(CopyFile(depJarsDir)), destination = OptAppDir, chown = Some(userAsOwner(UserInImage))),
          Instructions
            .Copy(sources = Seq(CopyFile(appJarsDir)), destination = OptAppDir, chown = Some(userAsOwner(UserInImage))))
      },
      dockerfile in docker := {
        val log = streams.value.log

        // this triggers side-effects, e.g. files being created in the staging area
        cloudflowStageAppJars.value

        cloudflowAkkaBaseImage.value match {
          case Some(baseImage) =>
            log.warn("'cloudflowAkkaBaseImage' is defined, 'cloudflowDockerBaseImage' setting is going to be ignored")

            val appDir: File = stage.value
            val appJarsDir: File = new File(appDir, AppJarsDir)
            val depJarsDir: File = new File(appDir, DepJarsDir)

            new Dockerfile {
              from(baseImage)
              user(UserInImage)
              copy(depJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
              copy(appJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
              addInstructions(extraDockerInstructions.value)
            }
          case _ =>
            new Dockerfile {
              from(cloudflowDockerBaseImage.value)
              addInstructions((ThisProject / baseDockerInstructions).value)
              addInstructions((ThisProject / extraDockerInstructions).value)
            }
        }
      })

  private lazy val akkaEntrypointContent: String =
    scala.io.Source
      .fromResource("runtimes/akka/akka-entrypoint.sh", getClass().getClassLoader())
      .getLines
      .mkString("\n")

  private lazy val prometheusYamlContent =
    scala.io.Source.fromResource("runtimes/akka/prometheus.yaml", getClass().getClassLoader()).getLines.mkString("\n")
}
