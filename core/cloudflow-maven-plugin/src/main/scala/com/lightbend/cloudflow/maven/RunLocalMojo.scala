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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
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

  def prepareLog4JFile(tempDir: Path, log4jConfigPath: Option[String]): Try[Path] =
    Try {
      val log4jClassResource = "local-run-log4j.properties"

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

    while (retry > 0) {
      val adminClient = AdminClient.create(
        Map[String, Object](
          AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaHost,
          AdminClientConfig.CLIENT_ID_CONFIG -> UUID.randomUUID().toString).asJava)
      try {
        topics.foreach { topic =>
          log.debug(s"Kafka Setup: creating topic: $topic")

          val newTopic = new NewTopic(topic, 1, 1.toShort)

          adminClient
            .createTopics(Seq(newTopic).asJava)
            .all
            .get()
        }

        retry = 0
      } catch {
        case _: Throwable =>
          retry -= 1
      } finally {
        adminClient.close()
      }
    }
  }

  def stopKafka() = Try {
    kafka.get().stop()
    kafka.set(null)
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
      // TODO: use a specific confg file if provided via property
      val localConfig = LocalConfig.load(None)

      // TODO make configurable
      val baseDebugPort = 5000

      val (tempDir, configDir) = createDirs("cloudflow-local-run")

      val streamletDescriptorsByProject = CloudflowAggregator.getStreamlets(allProjects, getLog)

      val descriptorByProject = allProjects.map { project =>
        val streamletClasses = streamletDescriptorsByProject(project.getName).keys.toSet
        project -> streamletFilterByClass(appDescriptor, streamletClasses)
      }

      val runtimeDescriptorByProject = getDescriptorsOrFail(
        descriptorByProject.map {
          case (p, projectDescriptor) =>
            p -> scaffoldRuntime(
              p.getName,
              projectDescriptor,
              localConfig,
              tempDir,
              configDir,
              None // TODO: restore local log4j file
            )
        },
        getLog())

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

      val processes = runtimeDescriptorByProject.zipWithIndex.map {
        case ((p, rd), debugPortOffset) =>
          val classpath = CloudflowAggregator.classpathByProject(p)
          runPipelineJVM(
            p.getName,
            rd.appDescriptorFile,
            classpath.toArray,
            rd.outputFile,
            rd.logConfig,
            rd.localConfPath,
            kafkaHost,
            false,
            baseDebugPort + debugPortOffset,
            None,
            getLog)
      }

      getLog.info(s"Running ${appDescriptor.appId}  \nTo terminate, press [ENTER]\n")

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

}
