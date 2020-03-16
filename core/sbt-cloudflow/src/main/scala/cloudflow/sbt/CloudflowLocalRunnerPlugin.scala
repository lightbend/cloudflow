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

import java.nio.file._

import scala.sys.process.Process
import scala.sys.SystemProperties
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._
import sbt._
import sbt.Keys._
import spray.json._

import cloudflow.blueprint.deployment.{ ApplicationDescriptor, StreamletDeployment, StreamletInstance }
import cloudflow.blueprint.deployment.ApplicationDescriptorJsonFormat._
import cloudflow.sbt.CloudflowKeys._
import cloudflow.streamlets.ServerAttribute

/**
 * SBT Plugin for running Cloudflow applications locally
 *
 */
object CloudflowLocalRunnerPlugin extends AutoPlugin {
  override def requires: Plugins = BlueprintVerificationPlugin
  override def trigger           = allRequirements

  val LocalRunnerClass = "cloudflow.runner.LocalRunner"

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    runLocal := Def.taskDyn {
          Def.task {
            implicit val logger = streams.value.log
            val _               = verifyBlueprint.value // force check blueprint with feedback
            val classpath       = cloudflowApplicationClasspath.value

            // setup local file scaffolding
            val (appId, appDescriptorFile, outputFile, log4jConfigFile) = (for {
              tempRuntimeDir            ← prepareTempDir("local-cloudflow")
              outputFile                ← prepareOutputFile(tempRuntimeDir)
              log4jConfigFile           ← prepareLog4JFileFromResource(tempRuntimeDir, "local-run-log4j.properties")
              (localConf, localConfMsg) ← preparePluginConfig(runLocalConfigFile.value)
              appDescriptor             ← prepareApplicationDescriptor(applicationDescriptor.value, localConf, tempRuntimeDir)
              appDescriptorFile         ← prepareApplicationFile(appDescriptor)
            } yield {
              logger.info(localConfMsg)
              printInfo(appDescriptor, outputFile)
              (appDescriptor.appId, appDescriptorFile, outputFile, log4jConfigFile)
            }).recoverWith {
              case NonFatal(ex) ⇒
                warningBanner(s"Setup failed. Reason: ${ex.getMessage}")
                Failure(ex)
            }.get //force resolution

            logger.debug("Using log4 config file at:" + log4jConfigFile)
            logger.debug("Using output log file at:" + outputFile)

            val process = runPipelineJVM(appDescriptorFile, classpath, outputFile, log4jConfigFile)

            println(s"Running ${appId}  \nTo terminate, press [ENTER]\n")

            try {
              sbt.internal.util.SimpleReader.readLine("")
              logger.info("Attempting to terminate local Pipeline")
              process.destroy()
            } catch {
              case ex: Throwable ⇒
                logger.warn("Stopping process failed.")
                ex.printStackTrace()
            }
          }
        }.value
  )

  def banner(bannerChar: Char)(name: String)(message: Any): Unit = {
    val title        = s" $name "
    val bannerLength = 80
    val sideLength   = (bannerLength - title.size) / 2
    val side         = List.fill(sideLength)(bannerChar).mkString("")
    val bottom       = List.fill(bannerLength)(bannerChar).mkString("")
    println(side + title + side)
    println(message.toString)
    println(bottom + "\n")
  }
  val infoBanner    = banner('-') _
  val warningBanner = banner('!') _

  def prepareLog4JFileFromResource(tempDir: Path, resourcePath: String)(implicit logger: Logger): Try[Path] = Try {
    val log4JSrc       = Option(this.getClass.getClassLoader.getResourceAsStream(resourcePath))
    val localLog4jFile = tempDir.resolve("local-log4j.properties")
    try {
      log4JSrc
        .map(src ⇒ Files.copy(src, localLog4jFile))
        .getOrElse {
          logger.warn("Could not find log4j configuration for local runner")
          0L
        }
    } finally {
      log4JSrc.foreach(_.close)
    }
    localLog4jFile
  }

  def prepareApplicationFile(applicationDescriptor: ApplicationDescriptor): Try[Path] = Try {
    val localApplicationFile = Files.createTempFile("local-runner", ".json")
    val contents             = applicationDescriptor.toJson.prettyPrint
    IO.write(localApplicationFile.toFile, contents)
    localApplicationFile
  }

  def prepareOutputFile(workDir: Path): Try[File] = Try {
    Files.createTempFile(workDir, "local-cloudflow", ".log").toFile
  }

  def prepareTempDir(prefix: String): Try[Path] = Try {
    Files.createTempDirectory(prefix)
  }

  def preparePluginConfig(configFile: Option[String]): Try[(Config, String)] = Try {
    configFile
      .map(filename ⇒ (ConfigFactory.parseFile(new File(filename)), s"Using sandbox configuration from $filename"))
      .getOrElse((ConfigFactory.empty(), NoLocalConfFoundMsg))
  }

  def prepareApplicationDescriptor(applicationDescriptor: Option[ApplicationDescriptor],
                                   config: Config,
                                   tempDir: Path): Try[ApplicationDescriptor] =
    (for {
      descriptor ← failOnEmpty(applicationDescriptor) {
        new RuntimeException("Invalid or missing application descriptor. This is certainly a bug. Please report it.")
      }
      updatedStreamlets ← tryOverrideVolumeMounts(descriptor.streamlets, config, tempDir)
    } yield {
      descriptor.copy(streamlets = updatedStreamlets)
    }).recoverWith {
      case NonFatal(ex) ⇒
        warningBanner("Setup Failure")(ex.getMessage)
        Failure(ex)
    }

  def runPipelineJVM(applicationDescriptorFile: Path, classpath: Array[URL], outputFile: File, log4JConfigFile: Path)(
      implicit logger: Logger
  ): Process = {
    val cp = "-cp"
    val separator = new SystemProperties().get("path.separator").getOrElse {
      logger.warn("No \"path.separator\" setting found. Using default value \":\" ")
      ":"
    }

    // Using file://localhost/path instead of file:///path or even file://path (as it was originally)
    // appears to be necessary for runLocal to work on both Windows and real systems.
    val forkOptions = ForkOptions()
      .withOutputStrategy(OutputStrategy.LoggedOutput(logger))
      .withConnectInput(false)
      .withRunJVMOptions(Vector(s"-Dlog4j.configuration=file:///${log4JConfigFile.toFile.getAbsolutePath}"))
    val classpathStr = classpath.map(url ⇒ new File(url.toURI)).mkString(separator)
    val options      = Seq(applicationDescriptorFile.toFile.getAbsolutePath, outputFile.getAbsolutePath)

    val cmd = Seq(cp, classpathStr, LocalRunnerClass) ++ options
    Fork.java.fork(forkOptions, cmd)
  }

  def failOnEmpty[T](opt: Option[T])(ex: ⇒ Exception): Try[T] = Try {
    opt.getOrElse(throw ex)
  }

  case class Exceptions(values: Seq[Throwable]) extends Exception(values.map(ex ⇒ ex.getMessage).mkString(", "))
  def tryOverrideVolumeMounts(
      streamlets: Vector[StreamletInstance],
      localConf: Config,
      localStorageDir: Path
  ): Try[Vector[StreamletInstance]] = {

    val updatedStreamlets = streamlets.map { streamlet ⇒
      val streamletLocalConf = if (localConf.hasPath(streamlet.name)) localConf.getConfig(streamlet.name) else ConfigFactory.empty()
      val volumeMounts       = streamlet.descriptor.volumeMounts
      val localVolumeMounts = volumeMounts.map { volumeMount ⇒
        val tryLocalPath = streamletLocalConf
          .as[Option[String]](volumeMount.name)
          .map(Success(_))
          .getOrElse {
            Try {
              val path = localStorageDir.resolve(volumeMount.name).toFile
              path.mkdirs() // create the temp dir
              path.getAbsolutePath
            }
          }
        tryLocalPath.map(localPath ⇒ volumeMount.copy(path = localPath))
      }
      foldExceptions(localVolumeMounts).map(volumeMounts ⇒
        streamlet.copy(descriptor = streamlet.descriptor.copy(volumeMounts = volumeMounts.toVector))
      )
    }
    foldExceptions(updatedStreamlets).map(_.toVector)
  }

  def foldExceptions[T](collection: Seq[Try[T]]): Try[Seq[T]] = {
    val zero: Try[Seq[T]] = Success(Seq())
    collection.foldLeft(zero) {
      case (Success(seq), Success(elem))          ⇒ Success(elem +: seq)
      case (Success(_), Failure(f))               ⇒ Failure(Exceptions(Seq(f)))
      case (Failure(f), Success(_))               ⇒ Failure(f)
      case (Failure(Exceptions(exs)), Failure(f)) ⇒ Failure(Exceptions(f +: exs))
      case (Failure(f1), Failure(f2))             ⇒ Failure(Exceptions(Seq(f1, f2)))
    }
  }

  def printInfo(appDescriptor: ApplicationDescriptor, outputFile: File): Unit = {
    val connections                                = appDescriptor.connections
    val streamletInstances: Seq[StreamletInstance] = appDescriptor.streamlets.sortBy(_.name)
    var endpointIdx                                = 0
    val streamletInfo = streamletInstances.map { streamlet ⇒
      val deployment = StreamletDeployment(appDescriptor.appId,
                                           streamlet,
                                           "",
                                           appDescriptor.connections,
                                           StreamletDeployment.EndpointContainerPort + endpointIdx)
      deployment.endpoint.foreach(_ => endpointIdx += 1)

      val serverPort: Option[Int] = if (deployment.config.hasPath(ServerAttribute.configPath)) {
        Some(ServerAttribute.containerPort(deployment.config))
      } else {
        None
      }

      def newLineIfNotEmpty(s: String): String = if (s.nonEmpty) s"\n$s" else s

      val volumeMounts = streamlet.descriptor.volumeMounts
        .map { mount ⇒
          s"\t- mount [${mount.name}] available at [${mount.path}]"
        }
        .mkString("\n")
      val endpointMessage = serverPort.map(port ⇒ s"\t- HTTP port [$port]").getOrElse("")
      s"${streamlet.name} [${streamlet.descriptor.className}]" +
        newLineIfNotEmpty(endpointMessage) +
        newLineIfNotEmpty(volumeMounts)
    }

    infoBanner("Streamlets")(streamletInfo.mkString("\n"))
    infoBanner("Connections")(
      connections.map(c ⇒ s"${c.outletStreamletName}.${c.outletName} -> ${c.inletStreamletName}.${c.inletName}").mkString("\n")
    )
    infoBanner("Output")(s"Pipeline log output available in file: " + outputFile)
  }

  val NoLocalConfFoundMsg = "No local.conf file location configured. \n" +
        "Set 'runLocalConfigFile' in your build to point to your local.conf location "

}
