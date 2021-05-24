/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.maven

import cloudflow.buildtool._
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.logging.Log
import org.apache.maven.plugin.{ AbstractMojo, BuildPluginManager }
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.MavenProject

import java.io.File
import java.net.URL
import java.nio.file.Path
import scala.collection.JavaConverters._
import scala.io.StdIn
import scala.sys.SystemProperties
import scala.util.{ Failure, Try }

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

  def execute(): Unit = {
    val topLevel = mavenSession.getTopLevelProject
    val projectId = topLevel.getName
    val version = topLevel.getVersion

    val allProjects = mavenSession.getAllProjects.asScala

    if (allProjects.last == mavenProject) {

      val cr = CloudflowProjectAggregator.getCR(
        CloudflowProjectAggregator
          .generateLocalCR(projectId = projectId, version = version, allProjects = allProjects, log = getLog()))

      val appDescriptor = cr.spec

      // load local config
      val loadedLocalConfig = LocalConfig.load(Option(localConfig))

      val (tempDir, configDir) = Scaffold.createDirs("cloudflow-local-run")

      val streamletDescriptorsByProject = CloudflowProjectAggregator.getStreamlets(allProjects, getLog)

      val descriptorByProject = allProjects.map { project =>
        val streamletClasses = streamletDescriptorsByProject(project.getName).keys.toSet
        if (streamletClasses.size > 0) {
          Some(project -> Scaffold.streamletFilterByClass(appDescriptor, streamletClasses))
        } else {
          None
        }
      }.flatten

      val runtimeDescriptorByProject = Scaffold.getDescriptorsOrFail(descriptorByProject.map {
        case (p, projectDescriptor) =>
          p -> Scaffold.scaffoldRuntime(
            p.getName,
            projectDescriptor,
            loadedLocalConfig,
            tempDir,
            configDir,
            prepareLog4JFile(configDir, Option(log4jConfig)),
            (f, c) => FileUtil.writeFile(f, c))
      })(getLog().error)

      val topics = appDescriptor.deployments
        .flatMap { deployment =>
          deployment.portMappings.values.map(_.name)
        }
        .distinct
        .sorted
      val kafkaHost = {
        val host = KafkaSupport.setupKafka(getLog().debug)
        KafkaSupport.createTopics(host, topics)(getLog().debug)
        host
      }

      PrintUtils.printInfo(
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
        KafkaSupport.stopKafka()
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
