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

object CloudflowSparkPlugin extends AutoPlugin {

  override def requires = CloudflowBasePlugin

  override def projectSettings =
    Seq(
      cloudflowSparkBaseImage := None,
      libraryDependencies ++= Vector(
          "com.lightbend.cloudflow" % s"cloudflow-spark_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value,
          "com.lightbend.cloudflow" % s"cloudflow-spark-testkit_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value % "test"),
      cloudflowDockerImageName := Def.task {
          Some(DockerImageName((ThisProject / name).value.toLowerCase, (ThisProject / version).value))
        }.value,
      buildOptions in docker := BuildOptions(
          cache = true,
          removeIntermediateContainers = BuildOptions.Remove.OnSuccess,
          pullBaseImage = BuildOptions.Pull.IfMissing),
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
              // Logback configuration
              // dependencies are filtered out here to preserve the behavior in runLocal
              if (!jar.getName.startsWith("slf4j-log4j12-1.7.16.jar") && !jar.getName.startsWith("log4j-1.2.17.jar"))
                IO.copyFile(jar, new File(appJarDir, jar.getName))
            }
            depJars.foreach { jar =>
              // Logback configuration
              // dependencies are filtered out here to preserve the behavior in runLocal
              if (!jar.getName.startsWith("slf4j-log4j12-1.7.16.jar") && !jar.getName.startsWith("log4j-1.2.17.jar"))
                IO.copyFile(jar, new File(depJarDir, jar.getName))
            }
          }
        }.value,
      baseDockerInstructions := {
        val appDir: File = stage.value
        val appJarsDir: File = new File(appDir, AppJarsDir)
        val depJarsDir: File = new File(appDir, DepJarsDir)

        val metricsProperties = (ThisProject / target).value / "cloudflow" / "spark" / "metrics.properties"
        IO.write(metricsProperties, metricsPropertiesContent)

        val prometheusYaml = (ThisProject / target).value / "cloudflow" / "spark" / "prometheus.yaml"
        IO.write(prometheusYaml, prometheusYamlContent)

        val log4jProperties = (ThisProject / target).value / "cloudflow" / "spark" / "log4j.properties"
        IO.write(log4jProperties, log4jPropertiesContent)

        val sparkEntrypointSh = (ThisProject / target).value / "cloudflow" / "spark" / "spark-entrypoint.sh"
        IO.write(sparkEntrypointSh, sparkEntrypointShContent)

        val scalaVersion = (ThisProject / scalaBinaryVersion).value
        val sparkVersion = "2.4.5"
        val sparkHome = "/opt/spark"

        val sparkTgz = s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}.tgz"
        val sparkTgzUrl = s"https://github.com/lightbend/spark/releases/download/${sparkVersion}-lightbend/${sparkTgz}"

        val tiniVersion = "v0.18.0"

        Seq(
          Instructions.Env("SPARK_HOME", sparkHome),
          Instructions.Env("SPARK_VERSION", sparkVersion),
          Instructions.Env("JAVA_OPTS", "-Dlogback.configurationFile=/opt/logging/logback.xml"),
          Instructions.Env("SPARK_JAVA_OPT_LOGGING", "-Dlogback.configurationFile=/opt/logging/logback.xml"),
          Instructions.Copy(CopyFile(metricsProperties), "/etc/metrics/conf/metrics.properties"),
          Instructions.Copy(CopyFile(prometheusYaml), "/etc/metrics/conf/prometheus.yaml"),
          Instructions.Copy(CopyFile(sparkEntrypointSh), "/opt/spark-entrypoint.sh"),
          Instructions.Copy(CopyFile(log4jProperties), "/tmp/log4j.properties"),
          Instructions.Run.shell(
            Seq(
              Seq("wget", sparkTgzUrl),
              Seq("tar", "-xvzf", sparkTgz),
              Seq("mkdir", "-p", sparkHome),
              Seq("cp", "-r", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/jars", s"${sparkHome}/jars"),
              Seq("cp", "-r", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/bin", s"${sparkHome}/bin"),
              Seq("cp", "-r", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/sbin", s"${sparkHome}/sbin"),
              Seq(
                "cp",
                "-r",
                s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/examples",
                s"${sparkHome}/examples"),
              Seq("cp", "-r", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/data", s"${sparkHome}/data"),
              Seq(
                "cp",
                s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/kubernetes/dockerfiles/spark/entrypoint.sh",
                "/opt/"),
              Seq("mkdir", "-p", s"${sparkHome}/conf"),
              Seq("cp", "/tmp/log4j.properties", s"${sparkHome}/conf/log4j.properties"),
              Seq("rm", sparkTgz),
              // logback configuration, based on:
              // https://stackoverflow.com/a/45479379
              // logback is provided by the streamlet
              Seq("rm", s"${sparkHome}/jars/slf4j-log4j12-1.7.16.jar"),
              Seq("rm", s"${sparkHome}/jars/log4j-1.2.17.jar"),
              Seq("rm", "-rf", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}"),
              Seq("chmod", "a+x", "/opt/spark-entrypoint.sh"),
              Seq("ln", "-s", "/lib", "/lib64"),
              Seq("apk", "add", "bash", "curl"),
              Seq("mkdir", "-p", "/opt/spark/work-dir"),
              Seq("touch", "/opt/spark/RELEASE"),
              Seq("chgrp", "root", "/etc/passwd"),
              Seq("chmod", "ug+rw", "/etc/passwd"),
              Seq("rm", "-rf", "/var/cache/apt/*"),
              Seq(
                "curl",
                "-L",
                s"https://github.com/krallin/tini/releases/download/${tiniVersion}/tini",
                "-o",
                "/sbin/tini"),
              Seq("chmod", "+x", "/sbin/tini"),
              Seq("addgroup", "-S", "-g", "185", "cloudflow"),
              Seq("adduser", "-u", "185", "-S", "-h", "/home/cloudflow", "-s", "/sbin/nologin", "cloudflow", "root"),
              Seq("adduser", "cloudflow", "cloudflow"),
              Seq("mkdir", "-p", "/prometheus"),
              Seq(
                "curl",
                "https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.11.0/jmx_prometheus_javaagent-0.11.0.jar",
                "-o",
                "/prometheus/jmx_prometheus_javaagent.jar"),
              Seq("chmod", "ug+rwX", "/home/cloudflow"),
              Seq("mkdir", "-p", "/opt/spark/conf"),
              Seq("chgrp", "-R", "0", "/opt/spark"),
              Seq("chmod", "-R", "g=u", "/opt/spark"),
              Seq("chmod", "-R", "u+x", "/opt/spark/bin"),
              Seq("chmod", "-R", "u+x", "/opt/spark/sbin"),
              Seq("chgrp", "-R", "0", "/prometheus"),
              Seq("chmod", "-R", "g=u", "/prometheus"),
              Seq("chgrp", "-R", "0", "/etc/metrics/conf"),
              Seq("chmod", "-R", "g=u", "/etc/metrics/conf"),
              Seq("chown", "cloudflow:root", "/opt"),
              Seq("chmod", "775", "/opt"),
              Seq("chmod", "g+w", "/opt/spark/work-dir")).reduce(_ ++ Seq("&&") ++ _)),
          Instructions.WorkDir("/opt/spark/work-dir"),
          Instructions.EntryPoint.exec(Seq("bash", "/opt/spark-entrypoint.sh")),
          Instructions.User(UserInImage),
          Instructions
            .Copy(sources = Seq(CopyFile(depJarsDir)), destination = OptAppDir, chown = Some(userAsOwner(UserInImage))),
          Instructions
            .Copy(sources = Seq(CopyFile(appJarsDir)), destination = OptAppDir, chown = Some(userAsOwner(UserInImage))),
          Instructions.Expose(Seq(4040)))
      },
      dockerfile in docker := {
        val log = streams.value.log

        IO.delete(((ThisProject / target).value / "docker"))

        // this triggers side-effects, e.g. files being created in the staging area
        cloudflowStageAppJars.value

        cloudflowSparkBaseImage.value match {
          case Some(baseImage) =>
            log.warn("'cloudflowFlinkBaseImage' is defined, 'cloudflowDockerBaseImage' setting is going to be ignored")

            val appDir: File = stage.value
            val appJarsDir: File = new File(appDir, AppJarsDir)
            val depJarsDir: File = new File(appDir, DepJarsDir)

            new Dockerfile {
              from(baseImage)
              user(UserInImage)
              copy(depJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
              copy(appJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
              addInstructions(extraDockerInstructions.value)
              expose(4040)
            }
          case _ =>
            new Dockerfile {
              from(cloudflowDockerBaseImage.value)
              addInstructions((ThisProject / baseDockerInstructions).value)
              addInstructions((ThisProject / extraDockerInstructions).value)
            }
        }
      })

  private lazy val metricsPropertiesContent =
    scala.io.Source
      .fromResource("runtimes/spark/metrics.properties", getClass().getClassLoader())
      .getLines
      .mkString("\n")

  private lazy val prometheusYamlContent =
    scala.io.Source.fromResource("runtimes/spark/prometheus.yaml", getClass().getClassLoader()).getLines.mkString("\n")

  private lazy val log4jPropertiesContent =
    scala.io.Source.fromResource("runtimes/spark/log4j.properties", getClass().getClassLoader()).getLines.mkString("\n")

  private lazy val sparkEntrypointShContent =
    scala.io.Source
      .fromResource("runtimes/spark/spark-entrypoint.sh", getClass().getClassLoader())
      .getLines
      .mkString("\n")

}
