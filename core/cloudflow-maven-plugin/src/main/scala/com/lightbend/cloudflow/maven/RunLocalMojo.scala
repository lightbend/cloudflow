package com.lightbend.cloudflow.maven

import cloudflow.blueprint.deployment._
import cloudflow.blueprint.deployment.ApplicationDescriptorJsonFormat._
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.logging.Log
import org.apache.maven.plugin.{ AbstractMojo, BuildPluginManager }
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.MavenProject
import org.testcontainers.{ utility => tcutility }
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.wait.strategy.Wait
import spray.json._

import java.io.{ File, FileInputStream, InputStream }
import java.net.URL
import java.nio.file.{ Files, Path, StandardCopyOption }
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.concurrent.duration.DurationInt
import scala.io.StdIn
import scala.sys.SystemProperties
import scala.util.{ Failure, Success, Try }

@Mojo(
  name = "run-local",
  aggregator = false,
  requiresDependencyResolution = ResolutionScope.COMPILE,
  requiresDependencyCollection = ResolutionScope.COMPILE,
  defaultPhase = LifecyclePhase.PACKAGE)
class RunLocalMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  var mavenProject: MavenProject = _

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  var mavenSession: MavenSession = _

  @Parameter(name = "localConfig")
  var localConfig: String = _

  @Parameter(name = "log4jConfigFile")
  var log4jConfig: String = _

  @Parameter(name = "baseDebugPort")
  var baseDebugPort: Int = _

  @Parameter(name = "remoteDebug")
  var remoteDebug: Boolean = _

  @Component
  var pluginManager: BuildPluginManager = _

  val LocalRunnerClass = "cloudflow.localrunner.LocalRunner"

  // TODO: Extract and helper module from the sbt-plugin!!!
  def createDirs(prefix: String): (Path, Path) = {
    val workDir = Files.createTempDirectory(prefix)
    val configDir = workDir.resolve("config")
    val configDirFile = configDir.toFile
    if (!configDirFile.exists()) {
      configDirFile.mkdirs()
    }
    (workDir, configDir)
  }

  def streamletFilterByClass(
      appDescriptor: ApplicationDescriptor,
      streamletClasses: Set[String]): ApplicationDescriptor = {
    val streamletInstances =
      appDescriptor.streamlets.filter(streamlet => streamletClasses(streamlet.descriptor.className))
    val deploymentDescriptor =
      appDescriptor.deployments.filter(streamletDeployment => streamletClasses(streamletDeployment.className))
    appDescriptor.copy(streamlets = streamletInstances, deployments = deploymentDescriptor)
  }

  case class RuntimeDescriptor(
      id: String,
      appDescriptor: ApplicationDescriptor,
      appDescriptorFile: Path,
      outputFile: File,
      logConfig: Path,
      localConfPath: Option[String])

  def getDescriptorsOrFail(
      descriptors: Iterable[(MavenProject, Try[RuntimeDescriptor])],
      logger: Log): Iterable[(MavenProject, RuntimeDescriptor)] = {
    descriptors
      .collect {
        case (_, Failure(ex)) =>
          logger.error(s"Determining runtime descriptors failed: ${ex.getMessage}")
          ex
      }
      .foreach { ex =>
        throw ex
      }
    descriptors.collect { case (p, Success(runtimeDescriptor)) => (p, runtimeDescriptor) }
  }

  val log4jDefaultContent =
    """
      |# Root logger option
      |log4j.rootLogger=INFO, stdout
      |
      |# Direct log messages to stdout
      |log4j.appender.stdout=org.apache.log4j.ConsoleAppender
      |log4j.appender.stdout.Target=System.out
      |log4j.appender.stdout.layout=org.apache.log4j.EnhancedPatternLayout
      |log4j.appender.stdout.layout.ConversionPattern=[%p] [%d{HH:mm:ss.SSS}] %c{2.}:%L %m%n
      |
      |log4j.logger.cloudflow=INFO
      |
      |# Noisy Exclusions
      |log4j.logger.org.apache.spark=ERROR
      |log4j.logger.org.spark_project=ERROR
      |log4j.logger.kafka=ERROR
      |log4j.logger.org.apache.flink=ERROR
      |""".stripMargin

  def prepareLog4JFile(tempDir: Path, log4jConfigPath: Option[String]): Try[Path] =
    Try {
      val log4jClassResource = "local-run-log4j.properties"

      // keeping the filename since log4j uses the prefix to load it as XML or properties.
      val (log4JSrc: String, filename: String) = log4jConfigPath
        .map { log4jPath =>
          val log4jFile = new File(log4jPath)
          if (log4jFile.exists && log4jFile.isFile) {
            FileUtil.readLines(log4jFile).mkString("\n") -> log4jFile.getName
          }
        }
        .getOrElse(log4jDefaultContent -> log4jClassResource)

      val stagedLog4jFile = tempDir.resolve(filename)
      FileUtil.writeFile(stagedLog4jFile.toFile, log4JSrc)
      stagedLog4jFile
    }.recoverWith {
      case ex: Throwable => Failure(new Exception("Failed to prepare the log4j file.", ex))
    }

  case class Exceptions(values: Seq[Throwable]) extends Exception(values.map(ex => ex.getMessage).mkString(", "))
  def tryOverrideVolumeMounts(
      streamlets: Vector[StreamletInstance],
      localConf: Config,
      localStorageDir: Path): Try[Vector[StreamletInstance]] = {

    val updatedStreamlets = streamlets.map { streamlet =>
      val streamletName = streamlet.name
      val confPath = s"cloudflow.streamlets.$streamletName.volume-mounts"
      val streamletVolumeConf =
        if (localConf.hasPath(confPath)) localConf.getConfig(confPath) else ConfigFactory.empty()
      val volumeMounts = streamlet.descriptor.volumeMounts
      val localVolumeMounts = volumeMounts.map { volumeMount =>
        val tryLocalPath = Try {
          streamletVolumeConf.getString(volumeMount.name)
        }.recoverWith {
          case _ =>
            Try {
              val path = localStorageDir.resolve(volumeMount.name).toFile
              path.mkdirs() // create the temp dir
              path.getAbsolutePath
            }
        }
        tryLocalPath.map(localPath => volumeMount.copy(path = localPath))
      }
      foldExceptions(localVolumeMounts).map(volumeMounts =>
        streamlet.copy(descriptor = streamlet.descriptor.copy(volumeMounts = volumeMounts.toVector)))
    }
    foldExceptions(updatedStreamlets).map(_.toVector)
  }

  def foldExceptions[T](collection: Seq[Try[T]]): Try[Seq[T]] = {
    val zero: Try[Seq[T]] = Success(Seq())
    collection.foldLeft(zero) {
      case (Success(seq), Success(elem))          => Success(elem +: seq)
      case (Success(_), Failure(f))               => Failure(Exceptions(Seq(f)))
      case (Failure(f), Success(_))               => Failure(f)
      case (Failure(Exceptions(exs)), Failure(f)) => Failure(Exceptions(f +: exs))
      case (Failure(f1), Failure(f2))             => Failure(Exceptions(Seq(f1, f2)))
    }
  }

  def prepareApplicationDescriptor(
      applicationDescriptor: ApplicationDescriptor,
      config: Config,
      tempDir: Path): Try[ApplicationDescriptor] = {
    val updatedStreamlets = tryOverrideVolumeMounts(applicationDescriptor.streamlets, config, tempDir)
    updatedStreamlets.map(streamlets => applicationDescriptor.copy(streamlets = streamlets))
  }

  def createOutputFile(workDir: Path, projectId: String): Try[File] = Try {
    val localFile = workDir.resolve(projectId + "-local.log").toFile
    if (!localFile.exists()) {
      Files.createFile(workDir.resolve(projectId + "-local.log")).toFile
    }
    localFile
  }

  def prepareApplicationFile(applicationDescriptor: ApplicationDescriptor): Try[Path] = Try {
    val localApplicationFile = Files.createTempFile("local-runner", ".json")
    val contents = applicationDescriptor.toJson.prettyPrint
    FileUtil.writeFile(localApplicationFile.toFile, contents)
    localApplicationFile
  }

  def scaffoldRuntime(
      projectId: String,
      descriptor: ApplicationDescriptor,
      localConfig: LocalConfig,
      targetDir: Path,
      configDir: Path,
      log4jConfigFile: Option[String]): Try[RuntimeDescriptor] = {
    val log4jConfig =
      prepareLog4JFile(configDir, log4jConfigFile)
    for {
      appDescriptor <- prepareApplicationDescriptor(descriptor, localConfig.content, targetDir)
      outputFile <- createOutputFile(targetDir, projectId)
      logFile <- log4jConfig
      appDescriptorFile <- prepareApplicationFile(appDescriptor)
    } yield {
      RuntimeDescriptor(appDescriptor.appId, appDescriptor, appDescriptorFile, outputFile, logFile, localConfig.path)
    }
  }

  val KafkaPort = 9093
  val kafka = new AtomicReference[KafkaContainer]()

  def setupKafka(log: Log) = {

    val cl = Thread.currentThread().getContextClassLoader()
    val kafkaPort =
      try {
        val c = getClass().getClassLoader()
        Thread.currentThread().setContextClassLoader(c)

        val k = new KafkaContainer(tcutility.DockerImageName.parse("confluentinc/cp-kafka:5.4.3"))
          .withExposedPorts(KafkaPort)
          .waitingFor(Wait.forLogMessage(".*Kafka startTimeMs.*\\n", 1))
        k.start()
        kafka.set(k)

        k.getMappedPort(KafkaPort)
      } finally {
        Thread.currentThread().setContextClassLoader(cl)
      }

    log.debug(s"Setting up Kafka broker in Docker on port: $kafkaPort")

    s"localhost:${kafkaPort}"
  }

  def createTopics(kafkaHost: String, topics: Seq[String], log: Log) = {
    import org.apache.kafka.clients.admin.{ AdminClient, AdminClientConfig, NewTopic }
    import scala.collection.JavaConverters._

    var retry = 5
    var adminClient: AdminClient = null

    while (retry > 0) {
      try {
        topics.foreach { topic =>
          log.debug(s"Kafka Setup: creating topic: $topic")

          if (adminClient == null) {
            adminClient = AdminClient.create(
              Map[String, Object](
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaHost,
                AdminClientConfig.CLIENT_ID_CONFIG -> UUID.randomUUID().toString).asJava)
          }

          val newTopic = new NewTopic(topic, 1, 1.toShort)

          adminClient
            .createTopics(Seq(newTopic).asJava)
            .all
            .get()
        }

        retry = 0
      } catch {
        case _: Throwable =>
          log.warn(s"Exception occurred while provisioning Kafka retrying ($retry)")
          retry -= 1
      } finally {
        if (adminClient != null) {
          adminClient.close(Duration.ofSeconds(30))
        }
      }
    }
  }

  def stopKafka() = Try {
    kafka.get().stop()
    kafka.set(null)
  }

  // Needs to match StreamletAttribute
  final val configPrefix = "cloudflow.internal"
  final def configSection: String = s"$configPrefix.$attributeName"
  final def configPath = s"$configSection.$configKey"
  final val attributeName = "server"
  final val configKey = "container-port"

  def streamletInfo(descriptor: ApplicationDescriptor): Seq[String] = {
    val streamletInstances: Seq[StreamletInstance] = descriptor.streamlets.sortBy(_.name)

    streamletInstances.map { streamlet =>
      val streamletDeployment = descriptor.deployments.find(_.streamletName == streamlet.name)
      val serverPort: Option[Int] = streamletDeployment.flatMap { sd =>
        if (sd.config.hasPath(configPath)) {
          Some(sd.config.getInt(configPath))
        } else {
          None
        }
      }

      def newLineIfNotEmpty(s: String): String = if (s.nonEmpty) s"\n$s" else s

      val volumeMounts = streamlet.descriptor.volumeMounts
        .map { mount =>
          s"\t- mount [${mount.name}] available at [${mount.path}]"
        }
        .mkString("\n")

      val endpointMessage = serverPort.map(port => s"\t- HTTP port [$port]").getOrElse("")
      s"${streamlet.name} [${streamlet.descriptor.className}]" +
      newLineIfNotEmpty(endpointMessage) +
      newLineIfNotEmpty(volumeMounts)
    }
  }

  val infoBanner = banner('-') _
  val warningBanner = banner('!') _

  def banner(bannerChar: Char)(name: String)(message: Any): Unit = {
    val title = s" $name "
    val bannerLength = 80
    val sideLength = (bannerLength - title.size) / 2
    val side = List.fill(sideLength)(bannerChar).mkString("")
    val bottom = List.fill(bannerLength)(bannerChar).mkString("")

    println(side + title + side)
    println(message.toString)
    println(bottom + "\n")
  }

  def printInfo(
      descriptors: Iterable[(String, RuntimeDescriptor)],
      outputFolder: File,
      topics: Seq[String],
      localConfMsg: String): Unit = {
    val streamletInfoPerProject = descriptors.map {
      case (pid, rd) => (pid, rd.outputFile, streamletInfo(rd.appDescriptor))
    }
    val streamletReport = streamletInfoPerProject.map {
      case (pid, outputFile, streamletInfo) =>
        s"$pid - output file: ${outputFile.toURI.toString}\n\n" + streamletInfo.foldLeft("") {
          case (agg, str) => s"$agg\t$str\n"
        }
    }
    infoBanner("Streamlets per project")(streamletReport.mkString("\n"))
    infoBanner("Topics")(topics.map(t => s"[$t]").mkString("\n"))
    infoBanner("Local Configuration")(localConfMsg)
    infoBanner("Output")(s"Pipeline log output available in folder: " + outputFolder)
  }

  def execute(): Unit = {
    val topLevel = mavenSession.getTopLevelProject
    val projectId = topLevel.getName
    val version = topLevel.getVersion

    val allProjects = mavenSession.getAllProjects.asScala

    if (allProjects.last == mavenProject) {

      val cr = CloudflowAggregator.getCR(
        CloudflowAggregator
          .generateLocalCR(projectId = projectId, version = version, allProjects = allProjects, log = getLog()))

      val appDescriptor = cr.spec

      // load local config
      val loadedLocalConfig = LocalConfig.load(Option(localConfig))

      val (tempDir, configDir) = createDirs("cloudflow-local-run")

      val streamletDescriptorsByProject = CloudflowAggregator.getStreamlets(allProjects, getLog)

      val descriptorByProject = allProjects.map { project =>
        val streamletClasses = streamletDescriptorsByProject(project.getName).keys.toSet
        if (streamletClasses.size > 0) {
          Some(project -> streamletFilterByClass(appDescriptor, streamletClasses))
        } else {
          None
        }
      }.flatten

      val runtimeDescriptorByProject = getDescriptorsOrFail(descriptorByProject.map {
        case (p, projectDescriptor) =>
          p -> scaffoldRuntime(p.getName, projectDescriptor, loadedLocalConfig, tempDir, configDir, Option(log4jConfig))
      }, getLog())

      val topics = appDescriptor.deployments
        .flatMap { deployment =>
          deployment.portMappings.values.map(_.name)
        }
        .distinct
        .sorted
      val kafkaHost = {
        val host = setupKafka(getLog()) // TODO -> avoid running Kafka under a setting
        createTopics(host, topics, getLog())
        host
      }

      printInfo(
        runtimeDescriptorByProject.map { case (k, v) => k.getName -> v },
        tempDir.toFile,
        topics,
        loadedLocalConfig.message)

      val processes = runtimeDescriptorByProject.zipWithIndex.map {
        case ((p, rd), debugPortOffset) =>
          val classpath = FileUtil
            .readLines(new File(p.getBuild.getDirectory, Constants.FULL_CLASSPATH))
            .mkString("")
            .split(Constants.PATH_SEPARATOR)
            .map(s => new URL(s))

          runPipelineJVM(
            p.getName,
            rd.appDescriptorFile,
            classpath.toArray,
            rd.outputFile,
            rd.logConfig,
            rd.localConfPath,
            kafkaHost,
            remoteDebug,
            baseDebugPort + debugPortOffset,
            None,
            getLog)
      }

      getLog.info(s"Running ${appDescriptor.appId}. To terminate, press [ENTER]")

      try {
        StdIn.readLine()
        getLog().info("Attempting to terminate local application")
        processes.foreach(_.destroy())
      } catch {
        case ex: Throwable =>
          getLog().warn("Stopping process failed.")
          ex.printStackTrace()
      } finally {
        stopKafka()
      }
    }
  }

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
      userJavaOptions: Option[String],
      logger: Log): scala.sys.process.Process = {
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
      .withOutputStrategy(OutputStrategy.StdoutOutput) //.LoggedOutput(outputFile))
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

}
