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
import java.io._

import scala.sys.process.Process
import scala.sys.SystemProperties
import scala.util.{ Failure, Success, Try }

import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._
import sbt._
import sbt.Keys._
import spray.json._
import com.github.mdr.ascii.layout._
import com.github.mdr.ascii.graph._
import net.manub.embeddedkafka.{ EmbeddedKafka, EmbeddedKafkaConfig }
import cloudflow.blueprint.deployment.{ ApplicationDescriptor, StreamletInstance }
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

  val LocalRunnerClass = "cloudflow.localrunner.LocalRunner"
  val Slf4jLog4jBridge = "org.slf4j" % "slf4j-log4j12" % "1.7.30"
  val Log4J            = "log4j" % "log4j" % "1.2.17"

  // kafka config keys
  val BootstrapServersKey = "bootstrap.servers"
  val EmbeddedKafkaKey    = "embedded-kafka"

  // kafka local values
  val KafkaPort = 9093

  // Banner decorators
  val infoBanner    = banner('-') _
  val warningBanner = banner('!') _

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    libraryDependencies ++= Vector(
          Slf4jLog4jBridge % Test,
          Log4J            % Test
        ),
    allApplicationClasspathByProject := (Def
          .taskDyn {
            val filter = ScopeFilter(inProjects(allProjectsWithStreamletScannerPlugin.value: _*))
            Def.task {
              val allValues = cloudflowApplicationClasspathByProject.all(filter).value
              allValues

            }
          })
          .value
          .toMap,
    allStreamletDescriptorsByProject := (Def
          .taskDyn {
            val filter = ScopeFilter(inProjects(allProjectsWithStreamletScannerPlugin.value: _*))
            Def.task {
              val allValues = streamletDescriptorsByProject.all(filter).value
              allValues
            }
          })
          .value
          .toMap,
    (Test / runLocal) := Def.taskDyn {
          Def.task {
            implicit val logger = streams.value.log
            val _               = verifyBlueprint.value // force evaluation of the blueprint with side-effect feedback
            val cpByProject     = allApplicationClasspathByProject.value
            val configFile      = runLocalConfigFile.value
            val streamletDescriptorsByProject = allStreamletDescriptorsByProject.value.filter {
              case (_, streamletMap) => streamletMap.nonEmpty
            }
            val _appDescriptor = applicationDescriptor.value
            val appDescriptor = _appDescriptor.getOrElse {
              logger.error("LocalRunner: ApplicationDescriptor is not present. This is a bug. Please report it.")
              throw new IllegalStateException("ApplicationDescriptor is not present")
            }

            val logDependencies = findLogLibsInPluginClasspath((fullClasspath in Test).value)

            val projects = streamletDescriptorsByProject.keys

            // load local config
            val localConfig = LocalConfig.load(configFile)

            val (tempDir, configDir) = createDirs("cloudflow-local-run")
            val descriptorByProject = projects.map { pid =>
              val streamletClasses = streamletDescriptorsByProject(pid).keys.toSet
              pid -> streamletFilterByClass(appDescriptor, streamletClasses)
            }

            val runtimeDescriptorByProject = getDescriptorsOrFail {
              descriptorByProject.map {
                case (pid, projectDescriptor) =>
                  pid -> scaffoldRuntime(
                        pid,
                        projectDescriptor,
                        localConfig,
                        tempDir,
                        configDir,
                        runLocalLog4jConfigFile.value
                      )
              }
            }

            val topics = appDescriptor.deployments
              .flatMap { deployment =>
                deployment.portMappings.values.map(_.name)
              }
              .distinct
              .sorted
            setupKafka(KafkaPort, topics)

            printAppLayout(resolveConnections(appDescriptor))
            printInfo(runtimeDescriptorByProject, tempDir.toFile, topics, localConfig.message)

            val processes = runtimeDescriptorByProject.map {
              case (pid, rd) =>
                val classpath               = cpByProject(pid)
                val loggingPatchedClasspath = prepareLoggingInClasspath(classpath, logDependencies)
                runPipelineJVM(rd.appDescriptorFile, loggingPatchedClasspath, rd.outputFile, rd.logConfig, rd.localConfPath)
            }

            println(s"Running ${appDescriptor.appId}  \nTo terminate, press [ENTER]\n")

            try {
              sbt.internal.util.SimpleReader.readLine("")
              logger.info("Attempting to terminate local application")
              processes.foreach(_.destroy())
            } catch {
              case ex: Throwable ⇒
                logger.warn("Stopping process failed.")
                ex.printStackTrace()
            } finally {
              stopKafka()
            }
          }
        }.value,
    printAppGraph := printApplicationGraph.value
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

  def prepareLoggingInClasspath(classpath: Array[URL], logDependencies: Seq[(String, URL)]): Array[URL] = {
    val filteredClasspath = classpath.filter(cp => !cp.toString.contains("logback")) // remove logback from the classpath
    logDependencies.foldLeft(filteredClasspath) {
      case (agg, (libName, url)) =>
        if (agg.find(u => u.toString.contains(libName)).isEmpty) { // add slf/log4j if not there
          agg :+ url
        } else agg
    }
  }

  def findLogLibsInPluginClasspath(classpath: Keys.Classpath): Seq[(String, URL)] = {
    val localClasspath = classpath.files.map(_.toURI.toURL).toArray
    val logLibs        = Seq(toURLSegment(Log4J), toURLSegment(Slf4jLog4jBridge))
    // forced `get` b/c these libraries are added to the classpath.
    logLibs.map(lib => lib -> localClasspath.find(_.toString.contains(lib)).get)
  }

  // transforms the organization and name of a module into the URL format used by the classpath resolution
  def toURLSegment(dep: ModuleID): String = {
    val org  = dep.organization.replaceAll("\\.", "/")
    val name = dep.name
    s"$org/$name"
  }

  case class RuntimeDescriptor(id: String,
                               appDescriptor: ApplicationDescriptor,
                               appDescriptorFile: Path,
                               outputFile: File,
                               logConfig: Path,
                               localConfPath: Option[String])

  def setupKafka(port: Int, topics: Seq[String])(implicit log: Logger) = {
    implicit val kafkaConfig = EmbeddedKafkaConfig(kafkaPort = port)
    EmbeddedKafka.start()
    log.debug(s"Setting up embedded Kafka broker on port: $port")

    topics.foreach { topic ⇒
      log.debug(s"Kafka Setup: creating topic: $topic")
      EmbeddedKafka.createCustomTopic(topic)
    }
  }
  def stopKafka() =
    EmbeddedKafka.stop()

  def getDescriptorsOrFail(
      descriptors: Iterable[(String, Try[RuntimeDescriptor])]
  )(implicit logger: Logger): Iterable[(String, RuntimeDescriptor)] = {
    descriptors
      .collect {
        case (_, Failure(ex)) =>
          logger.error(s"Determining runtime descriptors failed: ${ex.getMessage}")
          ex
      }
      .foreach { ex =>
        throw ex
      }
    descriptors.collect { case (pid, Success(runtimeDescriptor)) => (pid, runtimeDescriptor) }
  }

  def printApplicationGraph: Def.Initialize[Task[Unit]] = Def.task {
    implicit val logger = streams.value.log
    val _appDescriptor  = applicationDescriptor.value
    val appDescriptor = _appDescriptor.getOrElse {
      logger.error("LocalRunner: ApplicationDescriptor is not present. This is a bug. Please report it.")
      throw new IllegalStateException("ApplicationDescriptor is not present")
    }
    printAppLayout(resolveConnections(appDescriptor))
  }

  def resolveConnections(appDescriptor: ApplicationDescriptor): List[(String, String)] = {
    def topicFormat(topic: String): String =
      s"[$topic]"
    val streamletIOResolver = appDescriptor.streamlets.map { st =>
      val inlets  = st.descriptor.inlets.map(_.name)
      val outlets = st.descriptor.outlets.map(_.name)
      val inOut   = inlets.map(name => name -> "inlet") ++ outlets.map(name => name -> "outlet")
      st.name -> inOut.toMap
    }.toMap

    appDescriptor.deployments.flatMap { deployment =>
      val streamlet    = deployment.streamletName
      val inletOutlets = streamletIOResolver(streamlet)
      val topicsOtherStreamlet = deployment.portMappings.toSeq.map {
        case (port, topic) =>
          val formattedTopic = topicFormat(topic.name)
          val io             = inletOutlets(port)
          if (io == "inlet") {
            // TODO verify this
            s"$formattedTopic" -> s"${deployment.streamletName}"
          } else {
            // TODO verify this
            s"${deployment.streamletName}" -> s"$formattedTopic"
          }
      }
      topicsOtherStreamlet
    }.toList
  }

  def printAppLayout(connections: List[(String, String)]): Unit = {
    val vertices = connections.flatMap { case (a, b) => Seq(a, b) }.toSet
    val graph    = Graph(vertices = vertices, edges = connections)
    println(GraphLayout.renderGraph(graph))
  }

  def scaffoldRuntime(projectId: String,
                      descriptor: ApplicationDescriptor,
                      localConfig: LocalConfig,
                      targetDir: Path,
                      configDir: Path,
                      log4jConfigFile: Option[String]): Try[RuntimeDescriptor] = {
    val log4jConfig =
      prepareLog4JFile(configDir, log4jConfigFile)
    for {
      appDescriptor     ← prepareApplicationDescriptor(descriptor, localConfig.content, targetDir)
      outputFile        ← createOutputFile(targetDir, projectId)
      logFile           ← log4jConfig
      appDescriptorFile ← prepareApplicationFile(appDescriptor)
    } yield {
      RuntimeDescriptor(appDescriptor.appId, appDescriptor, appDescriptorFile, outputFile, logFile, localConfig.path)
    }
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

  def streamletFilterByClass(appDescriptor: ApplicationDescriptor, streamletClasses: Set[String]): ApplicationDescriptor = {
    val streamletInstances   = appDescriptor.streamlets.filter(streamlet => streamletClasses(streamlet.descriptor.className))
    val deploymentDescriptor = appDescriptor.deployments.filter(streamletDeployment => streamletClasses(streamletDeployment.className))
    appDescriptor.copy(streamlets = streamletInstances, deployments = deploymentDescriptor)
  }

  def prepareApplicationFile(applicationDescriptor: ApplicationDescriptor): Try[Path] = Try {
    val localApplicationFile = Files.createTempFile("local-runner", ".json")
    val contents             = applicationDescriptor.toJson.prettyPrint
    IO.write(localApplicationFile.toFile, contents)
    localApplicationFile
  }

  def createOutputFile(workDir: Path, projectId: String): Try[File] = Try {
    val localFile = workDir.resolve(projectId + "-local.log").toFile
    if (!localFile.exists()) {
      Files.createFile(workDir.resolve(projectId + "-local.log")).toFile
    }
    localFile
  }

  def createDirs(prefix: String): (Path, Path) = {
    val workDir       = Files.createTempDirectory(prefix)
    val configDir     = workDir.resolve("config")
    val configDirFile = configDir.toFile
    if (!configDirFile.exists()) {
      configDirFile.mkdirs()
    }
    (workDir, configDir)
  }

  def prepareApplicationDescriptor(applicationDescriptor: ApplicationDescriptor,
                                   config: Config,
                                   tempDir: Path): Try[ApplicationDescriptor] = {
    val updatedStreamlets = tryOverrideVolumeMounts(applicationDescriptor.streamlets, config, tempDir)
    updatedStreamlets.map(streamlets => applicationDescriptor.copy(streamlets = streamlets))
  }

  def runPipelineJVM(applicationDescriptorFile: Path,
                     classpath: Array[URL],
                     outputFile: File,
                     log4JConfigFile: Path,
                     localConfPath: Option[String])(
      implicit logger: Logger
  ): Process = {
    val cp = "-cp"
    val separator = new SystemProperties().get("path.separator").getOrElse {
      logger.warn("""No "path.separator" setting found. Using default value ":" """)
      ":"
    }

    // Using file://localhost/path instead of file:///path or even file://path (as it was originally)
    // appears to be necessary for runLocal to work on both Windows and real systems.
    val forkOptions = ForkOptions()
      .withOutputStrategy(OutputStrategy.LoggedOutput(logger))
      .withConnectInput(false)
      .withRunJVMOptions(Vector(s"-Dlog4j.configuration=file:///${log4JConfigFile.toFile.getAbsolutePath}"))
    val classpathStr = classpath.collect { case url if !url.toString.contains("logback") ⇒ new File(url.toURI) }.mkString(separator)
    val options: Seq[String] = Seq(
      Some(applicationDescriptorFile.toFile.getAbsolutePath),
      Some(outputFile.getAbsolutePath),
      localConfPath
    ).flatten

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
      val streamletName       = streamlet.name
      val confPath            = s"cloudflow.streamlets.$streamletName.volume-mounts"
      val streamletVolumeConf = if (localConf.hasPath(confPath)) localConf.getConfig(confPath) else ConfigFactory.empty()
      val volumeMounts        = streamlet.descriptor.volumeMounts
      val localVolumeMounts = volumeMounts.map { volumeMount ⇒
        val tryLocalPath = streamletVolumeConf
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

  def printInfo(descriptors: Iterable[(String, RuntimeDescriptor)], outputFolder: File, topics: Seq[String], localConfMsg: String): Unit = {
    val streamletInfoPerProject = descriptors.map { case (pid, rd) => (pid, rd.outputFile, streamletInfo(rd.appDescriptor)) }
    val streamletReport = streamletInfoPerProject.map {
      case (pid, outputFile, streamletInfo) =>
        s"$pid - output file: ${outputFile.toURI.toString}\n\n" + streamletInfo.foldLeft("") { case (agg, str) => s"$agg\t$str\n" }
    }
    infoBanner("Streamlets per project")(streamletReport.mkString("\n"))
    infoBanner("Topics")(topics.map(t => s"[$t]").mkString("\n"))
    infoBanner("Local Configuration")(localConfMsg)
    infoBanner("Output")(s"Pipeline log output available in folder: " + outputFolder)
  }

  def streamletInfo(descriptor: ApplicationDescriptor): Seq[String] = {
    val streamletInstances: Seq[StreamletInstance] = descriptor.streamlets.sortBy(_.name)

    streamletInstances.map { streamlet ⇒
      val streamletDeployment = descriptor.deployments.find(_.streamletName == streamlet.name)
      val serverPort: Option[Int] = streamletDeployment.flatMap { sd =>
        if (sd.config.hasPath(ServerAttribute.configPath)) {
          Some(ServerAttribute.containerPort(sd.config))
        } else {
          None
        }
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
  }

}
