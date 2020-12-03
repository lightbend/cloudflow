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
import java.io.File

object CloudflowAkkaPlugin extends AutoPlugin {

  override def requires = CloudflowBasePlugin

  override def projectSettings = Seq(
    cloudflowAkkaBaseImage := None,
    libraryDependencies ++= Vector(
          "com.lightbend.cloudflow" % s"cloudflow-akka-util_${(ThisProject / scalaBinaryVersion).value}"    % (ThisProject / cloudflowVersion).value,
          "com.lightbend.cloudflow" % s"cloudflow-akka_${(ThisProject / scalaBinaryVersion).value}"         % (ThisProject / cloudflowVersion).value,
          "com.lightbend.cloudflow" % s"cloudflow-akka-testkit_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value % "test"
        ),
    cloudflowStageAppJars := Def.taskDyn {
          Def.task {
            val stagingDir  = stage.value
            val projectJars = (Runtime / internalDependencyAsJars).value.map(_.data)
            val depJars     = (Runtime / externalDependencyClasspath).value.map(_.data)

            val appJarDir = new File(stagingDir, AppJarsDir)
            val depJarDir = new File(stagingDir, DepJarsDir)
            projectJars.foreach { jar =>
              IO.copyFile(jar, new File(appJarDir, jar.getName))
            }
            depJars.foreach { jar =>
              IO.copyFile(jar, new File(depJarDir, jar.getName))
            }
          }
        }.value,
    dockerfile in docker := {
      val log = streams.value.log
      // this triggers side-effects, e.g. files being created in the staging area
      cloudflowStageAppJars.value

      val appDir: File     = stage.value
      val appJarsDir: File = new File(appDir, AppJarsDir)
      val depJarsDir: File = new File(appDir, DepJarsDir)

      cloudflowAkkaBaseImage.value match {
        case Some(baseImage) =>
          log.warn("'cloudflowAkkaBaseImage' is defined, 'cloudflowDockerBaseImage' setting is going to be ignored")
          new Dockerfile {
            from(baseImage)
            user(UserInImage)
            copy(depJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
            copy(appJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
            addInstructions(extraDockerInstructions.value)
          }
        case _ =>
          val akkaEntrypointFile = (ThisProject / target).value / "cloudflow" / "akka" / "akka-entrypoint.sh"
          IO.write(akkaEntrypointFile, akkaEntrypointContent)

          new Dockerfile {
            from(cloudflowDockerBaseImage.value)
            user("root")
            run("apk", "add", "bash", "curl")
            run("mkdir", "-p", "/home/cloudflow")
            run("mkdir", "-p", "/opt")
            run("addgroup", "-g", "185", "-S", "cloudflow")
            run("adduser", "-u", "185", "-S", "-h", "/home/cloudflow", "-s", "/sbin/nologin", "cloudflow", "cloudflow")
            run("adduser", "cloudflow", "root")
            run("mkdir", "-p", "/prometheus")
            run(
              "curl",
              "https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.11.0/jmx_prometheus_javaagent-0.11.0.jar",
              "-o",
              "/prometheus/jmx_prometheus_javaagent.jar"
            )
            copy(akkaEntrypointFile, "/opt/akka-entrypoint.sh")
            run("chmod", "a+x", "/opt/akka-entrypoint.sh")
            user(UserInImage)
            entryPoint("bash", "/opt/akka-entrypoint.sh")
            copy(depJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
            copy(appJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
            addInstructions(extraDockerInstructions.value)
          }
      }
    }
  )

  private val akkaEntrypointContent =
    """#!/usr/bin/env bash
      |
      |# Base configuration
      |app_home="/app"
      |lib_dir="/opt/cloudflow"
      |java_main="cloudflow.runner.Runner"
      |
      |# Java agent(s)
      |agents="-javaagent:/prometheus/jmx_prometheus_javaagent.jar=2050:/etc/cloudflow-runner/prometheus.yaml"
      |
      |# Java options
      |java_opts="$agents $JAVA_OPTS"
      |
      |# Classpath Opts
      |app_config="/etc/cloudflow-runner"
      |java_classpath="$app_config:$lib_dir/*"
      |
      |echo "Cloudflow Runner"
      |echo "Java opts: $java_opts"
      |echo "Classpath: $java_classpath"
      |
      |exec java $java_opts -cp $java_classpath $java_main""".stripMargin
}
