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

import java.nio.file._
import java.io._

import scala.sys.process.Process
import scala.sys.SystemProperties
import scala.util.{ Failure, Success, Try }

import com.typesafe.config.Config
import sbt._
import sbt.Keys._
import cloudflow.buildtool._
import cloudflow.sbt.CloudflowKeys._
import cloudflow.extractor.ExtractResult

/**
 * SBT Plugin for running Cloudflow applications locally
 *
 */
object CloudflowLocalRunnerPlugin extends AutoPlugin {
  override def requires: Plugins = BlueprintVerificationPlugin
  override def trigger = allRequirements

  val LocalRunnerClass = "cloudflow.localrunner.LocalRunner"
  val Slf4jLog4jBridge = "org.slf4j" % "slf4j-log4j12" % "1.7.30"
  val Log4J = "log4j" % "log4j" % "1.2.17"

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      libraryDependencies ++= Vector(Slf4jLog4jBridge % Test, Log4J % Test),
      allApplicationClasspathByProject := Def
          .taskDyn {
            val filter = ScopeFilter(inProjects(allProjectsWithStreamletScannerPlugin.value: _*))
            Def.task {
              val allValues = cloudflowApplicationClasspathByProject.all(filter).value
              allValues

            }
          }
          .value
          .toMap,
      allStreamletDescriptorsByProject := Def
          .taskDyn {
            val filter = ScopeFilter(inProjects(allProjectsWithStreamletScannerPlugin.value: _*))
            Def.task {
              val allValues = streamletDescriptorsByProject.all(filter).value
              allValues
            }
          }
          .value
          .toMap,
      (Test / runLocal) := Def.taskDyn {
          Def.task {
            implicit val logger = streams.value.log
            val _ = verifyBlueprint.value // force evaluation of the blueprint with side-effect feedback
            val cpByProject = allApplicationClasspathByProject.value
            val configFile = runLocalConfigFile.value
            val streamletDescriptorsByProject: Map[String, Map[String, Config]] =
              allStreamletDescriptorsByProject.value.collect {
                case (str, ExtractResult(streamletMap, problems)) if streamletMap.nonEmpty && problems.isEmpty =>
                  str -> streamletMap
              }
            val _appDescriptor = applicationDescriptor.value
            val appDescriptor = _appDescriptor.getOrElse {
              logger.error("LocalRunner: ApplicationDescriptor is not present. This is a bug. Please report it.")
              throw new IllegalStateException("ApplicationDescriptor is not present")
            }

            val logDependencies = findLogLibsInPluginClasspath((fullClasspath in Test).value)

            val projects = streamletDescriptorsByProject.keys

            // load local config
            val localConfig = cloudflow.buildtool.LocalConfig.load(configFile)
            val baseDebugPort = initialDebugPort.value

            val (tempDir, configDir) = Scaffold.createDirs("cloudflow-local-run")
            val descriptorByProject = projects.map { pid =>
              val streamletClasses = streamletDescriptorsByProject(pid).keys.toSet
              pid -> Scaffold.streamletFilterByClass(appDescriptor, streamletClasses)
            }

            val runtimeDescriptorByProject = Scaffold.getDescriptorsOrFail {
              descriptorByProject.map {
                case (pid, projectDescriptor) =>
                  pid -> Scaffold.scaffoldRuntime(
                    pid,
                    projectDescriptor,
                    localConfig,
                    tempDir,
                    configDir,
                    prepareLog4JFile(configDir, runLocalLog4jConfigFile.value),
                    (f, c) => IO.write(f, c))
              }
            }(logger.error(_))

            val topics = appDescriptor.deployments
              .flatMap { deployment =>
                deployment.portMappings.values.map(_.name)
              }
              .distinct
              .sorted
            val kafkaHost = {
              val host = (ThisBuild / runLocalKafka).value.getOrElse(KafkaSupport.setupKafka(s => logger.debug(s)))
              KafkaSupport.createTopics(host, topics)(s => logger.debug(s))
              host
            }

            println(AppLayout.getAppLayout(AppLayout.resolveConnections(appDescriptor)))
            PrintUtils.printInfo(runtimeDescriptorByProject, tempDir.toFile, topics, localConfig.message)

            val processes = runtimeDescriptorByProject.zipWithIndex.map {
              case ((pid, rd), debugPortOffset) =>
                val classpath = cpByProject(pid)
                val loggingPatchedClasspath = prepareLoggingInClasspath(classpath, logDependencies)
                runPipelineJVM(
                  pid,
                  rd.appDescriptorFile,
                  loggingPatchedClasspath,
                  rd.outputFile,
                  rd.logConfig,
                  rd.localConfPath,
                  kafkaHost,
                  remoteDebugRunLocal.value,
                  baseDebugPort + debugPortOffset,
                  runLocalJavaOptions.value)
            }

            println(s"Running ${appDescriptor.appId}  \nTo terminate, press [ENTER]\n")

            try {
              sbt.internal.util.SimpleReader.readLine("")
              logger.info("Attempting to terminate local application")
              processes.foreach(_.destroy())
            } catch {
              case ex: Throwable =>
                logger.warn("Stopping process failed.")
                ex.printStackTrace()
            } finally {
              KafkaSupport.stopKafka()
            }
          }
        }.value,
      printAppGraph := printApplicationGraph.value,
      saveAppGraph := saveApplicationGraph.value)

  def prepareLoggingInClasspath(classpath: Array[URL], logDependencies: Seq[(String, URL)]): Array[URL] = {
    val filteredClasspath = classpath.filter(
      cp => !cp.toString.contains("logback") && !cp.toString.contains("log4j-over-slf4j")
    ) // remove logback from the classpath
    logDependencies.foldLeft(filteredClasspath) {
      case (agg, (libName, url)) =>
        if (agg.find(u => u.toString.contains(libName)).isEmpty) { // add slf/log4j if not there
          agg :+ url
        } else agg
    }
  }

  def findLogLibsInPluginClasspath(classpath: Keys.Classpath): Seq[(String, URL)] = {
    val localClasspath = classpath.files.map(_.toURI.toURL).toArray
    val logLibs = Seq(toURLSegment(Log4J), toURLSegment(Slf4jLog4jBridge))
    // forced `get` b/c these libraries are added to the classpath.
    logLibs.map(lib => lib -> localClasspath.find(path => dotToSlash(path.toString).contains(lib)).get)
  }

  def dotToSlash(name: String): String =
    name.replaceAll("\\.", "/")

  // transforms the organization and name of a module into the URL format used by the classpath resolution
  def toURLSegment(dep: ModuleID): String = {
    val org = dotToSlash(dep.organization)
    val name = dep.name
    s"$org/$name"
  }

  def printApplicationGraph: Def.Initialize[Task[Unit]] = Def.task {
    implicit val logger = streams.value.log
    val _appDescriptor = applicationDescriptor.value
    val appDescriptor = _appDescriptor.getOrElse {
      logger.error("LocalRunner: ApplicationDescriptor is not present. This is a bug. Please report it.")
      throw new IllegalStateException("ApplicationDescriptor is not present")
    }
    println(AppLayout.getAppLayout(AppLayout.resolveConnections(appDescriptor)))
  }

  def saveApplicationGraph: Def.Initialize[Task[File]] = Def.task {
    implicit val logger = streams.value.log
    val _appDescriptor = applicationDescriptor.value
    val appGraphDir = appGraphSavePath.value
    val appGraphFile = appGraphDir / "appGraph.txt"
    val appDescriptor = _appDescriptor.getOrElse {
      logger.error("LocalRunner: ApplicationDescriptor is not present. This is a bug. Please report it.")
      throw new IllegalStateException("ApplicationDescriptor is not present")
    }
    val layoutGraph = AppLayout.getAppLayout(AppLayout.resolveConnections(appDescriptor))
    IO.write(appGraphFile, layoutGraph)
    logger.info(s"App graph file is generated: $appGraphFile")
    appGraphFile
  }

  def prepareLog4JFile(tempDir: Path, log4jConfigPath: Option[String]): Try[Path] =
    Try {
      val log4jClassResource = CloudflowApplicationPlugin.DefaultLocalLog4jConfigFile

      if (this.getClass.getClassLoader.getResource(log4jClassResource) == null) {
        throw new Exception("Default log4j configuration could not be found on classpath of sbt-cloudflow.")
      }
      // keeping the filename since log4j uses the prefix to load it as XML or properties.
      val (log4JSrc: InputStream, filename: String) = log4jConfigPath
        .map { log4jPath =>
          val log4jFile = new File(log4jPath)
          if (log4jFile.exists && log4jFile.isFile) new FileInputStream(log4jFile) -> log4jFile.getName
        }
        .getOrElse(this.getClass.getClassLoader.getResourceAsStream(log4jClassResource) -> log4jClassResource)

      try {
        val stagedLog4jFile = tempDir.resolve(filename)
        Files.copy(log4JSrc, stagedLog4jFile, StandardCopyOption.REPLACE_EXISTING)
        stagedLog4jFile
      } finally {
        log4JSrc.close
      }
    }.recoverWith {
      case ex: Throwable => Failure(new Exception("Failed to prepare the log4j file.", ex))
    }

  //side-effects
  def runPipelineJVM(
      pid: String,
      applicationDescriptorFile: Path,
      classpath: Array[URL],
      outputFile: File,
      log4JConfigFile: Path,
      localConfPath: Option[String],
      kafkaHost: String,
      remoteDebug: Boolean,
      debugPort: Int,
      userJavaOptions: Option[String])(implicit logger: Logger): Process = {
    val cp = "-cp"
    val separator = new SystemProperties().get("path.separator").getOrElse {
      logger.warn("""No "path.separator" setting found. Using default value ":" """)
      ":"
    }

    val jvmOptions = {
      val baseOptions = Vector(s"-Dlog4j.configuration=file:///${log4JConfigFile.toFile.getAbsolutePath}")

      val extraOptions = {
        if (remoteDebug) {
          logger.info(s"listening for debugging '$pid' at 'localhost:$debugPort'")
          Vector(s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,quiet=y,address=localhost:$debugPort")
        } else {
          Vector()
        }
      }

      baseOptions ++ extraOptions ++ userJavaOptions.toVector
    }

    // Using file://localhost/path instead of file:///path or even file://path (as it was originally)
    // appears to be necessary for runLocal to work on both Windows and real systems.
    val forkOptions = ForkOptions()
      .withOutputStrategy(OutputStrategy.LoggedOutput(logger))
      .withConnectInput(false)
      .withRunJVMOptions(jvmOptions)

    val classpathStr =
      classpath.collect { case url if !url.toString.contains("logback") => new File(url.toURI) }.mkString(separator)

    val options: Seq[String] = Seq(
      Some(applicationDescriptorFile.toFile.getAbsolutePath),
      Some(outputFile.getAbsolutePath),
      Some(kafkaHost),
      localConfPath).flatten

    val cmd = Seq(cp, classpathStr, LocalRunnerClass) ++ options
    Fork.java.fork(forkOptions, cmd)
  }

  def failOnEmpty[T](opt: Option[T])(ex: => Exception): Try[T] = Try {
    opt.getOrElse(throw ex)
  }

}
