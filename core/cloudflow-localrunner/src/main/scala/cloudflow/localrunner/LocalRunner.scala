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

package cloudflow.localrunner

import java.io.{ Closeable, File, FileOutputStream, OutputStream, PrintStream }
import java.lang.{ Runtime => JRuntime }
import java.nio.file._
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal
import com.typesafe.config.{ Config, ConfigFactory }
import org.slf4j.LoggerFactory
import spray.json._
import cloudflow.blueprint.deployment.{ ApplicationDescriptor, StreamletDeployment, StreamletInstance, Topic }
import cloudflow.blueprint.deployment.ApplicationDescriptorJsonFormat._
import cloudflow.blueprint.RunnerConfigUtils._
import cloudflow.streamlets.{ BooleanValidationType, DoubleValidationType, IntegerValidationType, StreamletExecution, StreamletLoader }
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config._

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Local runner for sandbox testing. Because this is executed on Linux, MacOS, and
 * Windows, all path specifications must be compatible with all three platforms!
 */
object LocalRunner extends StreamletLoader {

  val consoleOut = System.out // preserve
  val errOut     = System.err // preserve

  lazy val log = LoggerFactory.getLogger("localRunner")

  def shutdownHook(outputStream: OutputStream) =
    new Thread(new Runnable {
      def run() = {
        System.setOut(consoleOut)
        System.setErr(errOut)
        withResourceDo(outputStream)(_.flush)
      }
    })

  val BootstrapServersKey = "bootstrap.servers"

  /**
   * Starts the local runner using an Application Descriptor JSON file and
   * the file in the local system where the output is going to be written.
   *
   * @param args: args(0) must be the JSON-encoded Application Descriptor
   *             args(1) must be the file to use for the output
   *             args(2) must be the kafka instance to use
   */
  def main(args: Array[String]): Unit = {
    val usage = "Usage: localRunner <applicationFileJson> <outputFile> <kafka-host> [localConfigFile]"
    val (appDescriptorFilename, outputFilename, kafkaHost, localConfig) = args.toList match {
      case app :: out :: kafkaHost :: conf :: Nil => (app, out, kafkaHost, ConfigFactory.parseFile(new File(conf)).resolve)
      case app :: out :: kafkaHost :: Nil         => (app, out, kafkaHost, ConfigFactory.empty())
      case Nil                                    => throw new RuntimeException(s"Missing application configuration file and output file for Local Runner\n$usage")
      case _ :: Nil                               => throw new RuntimeException(s"Missing output file for Local Runner\n$usage")
      case _ :: _ :: Nil                          => throw new RuntimeException(s"Missing kafka port\n$usage")
      case _                                      => throw new RuntimeException(s"Missing parameters for Local Runner. \n$usage")
    }

    val outputFile = new File(outputFilename)
    require(outputFile.exists(), s"Output file [${outputFile}] must exist before starting this process")

    val fos = new FileOutputStream(outputFile)
    JRuntime.getRuntime.addShutdownHook(shutdownHook(fos))

    Console.withOut(fos) {
      Console.withErr(fos) {
        System.setOut(new PrintStream(fos))
        System.setErr(new PrintStream(fos))
        readDescriptorFile(appDescriptorFilename) match {
          case Success(applicationDescriptor) =>
            run(applicationDescriptor, localConfig, kafkaHost)
          case Failure(ex) =>
            log.error(s"Failed JSON unmarshalling of application descriptor file [${appDescriptorFilename}].", ex)
            System.exit(1)
        }
      }
    }
  }

  private val mapper = new ObjectMapper().registerModule(new DefaultScalaModule())
  private def getRunnerConfig(appId: String, appVersion: String, deployment: StreamletDeployment): String = {
    def toJsonNode(config: Config) =
      mapper.readTree(config.root().render(ConfigRenderOptions.concise().setJson(true).setOriginComments(false).setComments(false)))

    val streamletConfig = cloudflow.runner.config.Streamlet(
      className = deployment.className,
      streamletRef = deployment.streamletName,
      context = cloudflow.runner.config.StreamletContext(
        appId = appId,
        appVersion = appVersion,
        config = toJsonNode(deployment.config),
        volumeMounts = deployment.volumeMounts.getOrElse(List.empty).map { vm =>
          cloudflow.runner.config.VolumeMount(name = vm.name, path = vm.path, accessMode = vm.accessMode)
        },
        portMappings = deployment.portMappings.map {
          case (name, topic) =>
            name -> cloudflow.runner.config.Topic(id = topic.id,
                                                  // TODO: check with Ray the default
                                                  cluster = topic.cluster.getOrElse(""),
                                                  config = toJsonNode(topic.config))
        }
      )
    )
    cloudflow.runner.config.toJson(streamletConfig)
  }

  private def run(appDescriptor: ApplicationDescriptor, localConfig: Config, kafkaHost: String): Unit = {
    val bootstrapServers =
      if (localConfig.hasPath(BootstrapServersKey)) localConfig.getString(BootstrapServersKey) else kafkaHost
    val topicConfig = ConfigFactory.parseString(s"""bootstrap.servers = "$bootstrapServers"""")

    val appId      = appDescriptor.appId
    val appVersion = appDescriptor.appVersion
    val baseConfig = ConfigFactory.load()

    val streamlets = appDescriptor.streamlets.sortBy(_.name)

    var endpointIdx = 0
    val streamletsWithConf = streamlets.map { streamletInstance =>
      val streamletName = streamletInstance.name
      val streamletParamConfig = resolveLocalStreamletConf(streamletInstance, localConfig).recoverWith {
        case missingConfEx: MissingConfigurationException =>
          log.error("Missing streamlet configuration: \n" + missingConfEx.keys.mkString("\n"))
          log.error("Configuration for local running is resolved the configuration `runLocalConfigFile` in the build.sbt")
          Failure(missingConfEx)
      }.get
      // Make sure that we convert any backslash in the path to a forward slash since we want to store this in a JSON value
      val localStorageDirectory =
        Files.createTempDirectory(s"local-runner-storage-${streamletName}").toFile.getAbsolutePath.replace('\\', '/')
      log.info(s"Using local storage directory: $localStorageDirectory")

      val existingPortMappings =
        appDescriptor.deployments
          .find(_.streamletName == streamletInstance.name)
          // Override topic configs to use the local runner configured Kafka broker
          .map(_.portMappings.mapValues(_.copy(cluster = None, config = topicConfig)))
          .getOrElse(Map.empty[String, Topic])

      val deployment: StreamletDeployment =
        StreamletDeployment(appDescriptor.appId,
                            streamletInstance,
                            "",
                            existingPortMappings.toMap,
                            StreamletDeployment.EndpointContainerPort + endpointIdx)
      deployment.endpoint.foreach(_ => endpointIdx += 1)

      val runnerConfigObj = getRunnerConfig(appId, appVersion, deployment)
      val runnerConfig    = addStorageConfig(ConfigFactory.parseString(runnerConfigObj), localStorageDirectory)

      val patchedRunnerConfig = runnerConfig
        .withFallback(streamletParamConfig)
        .withFallback(baseConfig)
        .withFallback(localConfig)
        .withValue("cloudflow.local", ConfigValueFactory.fromAnyRef(true))

      (streamletInstance, patchedRunnerConfig)
    }

    val launchedStreamlets = streamletsWithConf.map {
      case (streamletDescriptor, config) =>
        loadStreamletClass(streamletDescriptor.descriptor.className)
          .map { streamlet =>
            log.info(s"Preparing to run streamlet: [${streamletDescriptor.name}]")
            streamlet.run(config)
          }
          .recoverWith {
            case NonFatal(ex) =>
              log.error("Streamlet execution failed.", ex)
              Failure(StreamletLaunchFailure(streamletDescriptor.name, ex))
          }
    }

    reportAndExitOnFailure(launchedStreamlets)

    val pipelineExecution = Future.sequence {
      launchedStreamlets.collect { case Success(streamletExecution) => streamletExecution.completed }
    }

    Await.ready(pipelineExecution, Duration.Inf).onComplete {
      case Success(_) =>
        log.info("Application terminated successfully")
        System.exit(0)
      case Failure(ex) => {
        log.error("Failure in streamlet execution", ex)
        ex.printStackTrace()
        System.exit(-1)
      }
    }

  }

  private def reportAndExitOnFailure(launchedStreamlets: Vector[Try[StreamletExecution]], exit: => Unit = System.exit(-1)): Unit = {
    val failed = launchedStreamlets.collect { case Failure(ex) => ex }
    if (failed.nonEmpty) {
      log.error("The application can't be started.")
      failed.foreach { ex =>
        log.error(ex.getMessage, ex)
      }
      exit
    }
  }

  case class StreamletLaunchFailure(streamletName: String, failure: Throwable)
      extends Exception(s"Streamlet [$streamletName] failed to launch. Reason: ${failure.getMessage}", failure)

  private def resolveLocalStreamletConf(streamletDescriptor: StreamletInstance, localConf: Config): Try[Config] = {
    // streamlet implementations read their parameter config from the path `cloudflow.streamlets.${streamletRef}`
    val streamletParamConfig: Seq[(String, String, Option[String])] = streamletDescriptor.descriptor.configParameters.map {
      configParamDescriptor =>
        val sourceKey      = s"cloudflow.streamlets.${streamletDescriptor.name}.config-parameters.${configParamDescriptor.key}"
        val targetKey      = s"cloudflow.streamlets.${streamletDescriptor.name}.${configParamDescriptor.key}"
        val validationType = configParamDescriptor.validationType
        val value = if (localConf.hasPath(sourceKey)) {
          Some(localConf.getString(sourceKey))
        } else {
          configParamDescriptor.defaultValue
        }
        (targetKey, validationType, value)
    }

    val streamletConfig = {
      val streamletSourceKey = s"cloudflow.streamlets.${streamletDescriptor.name}.config"
      val runtimeSourceKey   = s"cloudflow.runtimes.${streamletDescriptor.descriptor.runtime.name}.config"

      val streamletConf =
        if (localConf.hasPath(streamletSourceKey)) {
          localConf.getConfig(streamletSourceKey)
        } else {
          ConfigFactory.empty()
        }

      val runtimeConf =
        if (localConf.hasPath(runtimeSourceKey)) {
          localConf.getConfig(runtimeSourceKey)
        } else {
          ConfigFactory.empty()
        }

      streamletConf.withFallback(runtimeConf)
    }

    val missingValues = streamletParamConfig.collect { case (k, _, None) => k }
    if (missingValues.nonEmpty) {
      Failure(MissingConfigurationException(missingValues))
    } else {
      Success {
        // I'll let the consumer of this configuration to parse the values as they want.
        // This quoting policy is here to preserve the type of the value in the resulting config obj
        ConfigFactory
          .parseString {
            streamletParamConfig
              .collect {
                case (key, validationType, Some(value)) =>
                  s"$key : ${quotePolicy(validationType)(value)}"
              }
              .mkString("\n")
          }
          .withFallback(streamletConfig)
      }
    }
  }

  private val isNonQuotedType = Set(BooleanValidationType.`type`, IntegerValidationType.`type`, DoubleValidationType.`type`)

  private def quotePolicy(validationType: String): String => String = { x =>
    if (isNonQuotedType(validationType)) x else s""""$x""""

  }

  private def readDescriptorFile(appDescriptorFilename: String): Try[ApplicationDescriptor] =
    Try {
      JsonParser(ParserInput(Files.readAllBytes(Paths.get(appDescriptorFilename))))
        .convertTo[ApplicationDescriptor]
    }.recoverWith {
      case NonFatal(ex) =>
        log.error(s"Failed to load application descriptor file [${appDescriptorFilename}].", ex)
        Failure(ex)
    }

  def withResourceDo[T <: Closeable](closeable: T)(f: T => Unit): Unit =
    try {
      f(closeable)
    } catch {
      case NonFatal(_) => ()
    } finally {
      try {
        closeable.close()
      } catch {
        case NonFatal(_) => ()
      }
    }

  final case class MissingConfigurationException(keys: Seq[String])
      extends Exception("Missing streamlet configuration(s): " + keys.mkString(","))
}
