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

package cloudflow.localrunner

import java.io.{ Closeable, File, FileOutputStream, OutputStream, PrintStream }
import java.lang.{ Runtime => JRuntime }
import java.nio.file._

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal
import com.typesafe.config.{ Config, ConfigFactory }
import org.slf4j.{ Logger, LoggerFactory, Marker }
import spray.json._
import cloudflow.blueprint.deployment.{ ApplicationDescriptor, RunnerConfig, StreamletDeployment, StreamletInstance, Topic }
import cloudflow.blueprint.deployment.ApplicationDescriptorJsonFormat._
import cloudflow.blueprint.RunnerConfigUtils._
import cloudflow.streamlets.{ BooleanValidationType, DoubleValidationType, IntegerValidationType, StreamletExecution, StreamletLoader }
import com.typesafe.config._

import scala.concurrent.ExecutionContext.Implicits.global

object FreakingLog extends Logger {
  override def getName: String = "freaking log"

  override def isTraceEnabled: Boolean = false

  override def trace(msg: String): Unit = ()

  override def trace(format: String, arg: Any): Unit = ()

  override def trace(format: String, arg1: Any, arg2: Any): Unit = ()

  override def trace(msg: String, t: Throwable): Unit = ()

  override def isTraceEnabled(marker: Marker): Boolean = false

  override def trace(marker: Marker, msg: String): Unit = ()

  override def trace(marker: Marker, format: String, arg: Any): Unit = ()

  override def trace(marker: Marker, format: String, arg1: Any, arg2: Any): Unit = ()

  override def trace(marker: Marker, msg: String, t: Throwable): Unit = ()

  override def isDebugEnabled: Boolean = false

  override def debug(msg: String): Unit = ()

  override def debug(format: String, arg: Any): Unit = ()

  override def debug(format: String, arg1: Any, arg2: Any): Unit = ()

  override def debug(msg: String, t: Throwable): Unit = ()

  override def isDebugEnabled(marker: Marker): Boolean = false

  override def debug(marker: Marker, msg: String): Unit = ()

  override def debug(marker: Marker, format: String, arg: Any): Unit = ()

  override def debug(marker: Marker, format: String, arg1: Any, arg2: Any): Unit = ()

  override def debug(marker: Marker, msg: String, t: Throwable): Unit = ()

  override def isInfoEnabled: Boolean = true

  override def info(msg: String): Unit = println("I]> " + msg)

  override def info(format: String, arg: Any): Unit = println(format)

  override def info(format: String, arg1: Any, arg2: Any): Unit = println(format)

  override def info(msg: String, t: Throwable): Unit = println(msg + " ::: " + t.getMessage)

  override def isInfoEnabled(marker: Marker): Boolean = true

  override def info(marker: Marker, msg: String): Unit = println(msg)

  override def info(marker: Marker, format: String, arg: Any): Unit = println(format)

  override def info(marker: Marker, format: String, arg1: Any, arg2: Any): Unit = println(format)

  override def info(marker: Marker, msg: String, t: Throwable): Unit = println(msg + " ::: " + t.getMessage)

  override def isWarnEnabled: Boolean = true

  override def warn(msg: String): Unit = println(msg)

  override def warn(format: String, arg: Any): Unit = println(format)

  override def warn(format: String, arg1: Any, arg2: Any): Unit = println(format)

  override def warn(msg: String, t: Throwable): Unit = println(msg)

  override def isWarnEnabled(marker: Marker): Boolean = true

  override def warn(marker: Marker, msg: String): Unit = println(msg)

  override def warn(marker: Marker, format: String, arg: Any): Unit = println(format)

  override def warn(marker: Marker, format: String, arg1: Any, arg2: Any): Unit = println(format)

  override def warn(marker: Marker, msg: String, t: Throwable): Unit = println(msg)

  override def isErrorEnabled: Boolean = true

  override def error(msg: String): Unit = println(msg)

  override def error(format: String, arg: Any): Unit = println(format)

  override def error(format: String, arg1: Any, arg2: Any): Unit = println(format)

  override def error(msg: String, t: Throwable): Unit = println(msg)

  override def isErrorEnabled(marker: Marker): Boolean = true

  override def error(marker: Marker, msg: String): Unit = println(msg)

  override def error(marker: Marker, format: String, arg: Any): Unit = println(format)

  override def error(marker: Marker, format: String, arg1: Any, arg2: Any): Unit = println(format)

  override def error(marker: Marker, msg: String, t: Throwable): Unit = println(msg)

  override def debug(x$1: org.slf4j.Marker, x$2: String, x$3: Object*): Unit = ???
  override def debug(x$1: String, x$2: Object*): Unit                        = ???
  override def error(x$1: org.slf4j.Marker, x$2: String, x$3: Object*): Unit = ???
  override def error(x$1: String, x$2: Object*)                              = println(x$1)
  override def info(x$1: org.slf4j.Marker, x$2: String, x$3: Object*): Unit  = println(x$2)
  override def info(x$1: String, x$2: Object*)                               = println(x$1)
  override def trace(x$1: org.slf4j.Marker, x$2: String, x$3: Object*): Unit = ???
  override def trace(x$1: String, x$2: Object*): Unit                        = ???
  override def warn(x$1: org.slf4j.Marker, x$2: String, x$3: Object*): Unit  = println(x$2)
  override def warn(x$1: String, x$2: Object*): Unit                         = println(x$1)

}

/**
 * Local runner for sandbox testing. Because this is executed on Linux, MacOS, and
 * Windows, all path specifications must be compatible with all three platforms!
 */
object LocalRunner extends StreamletLoader {

  val consoleOut = System.out // preserve

  lazy val log = LoggerFactory.getLogger("localRunner")

  def shutdownHook(outputStream: OutputStream) =
    new Thread(new Runnable {
      def run() {
        System.setOut(consoleOut)
        withResourceDo(outputStream)(_.flush)
      }
    })

  lazy val localConf = Option(this.getClass.getClassLoader.getResource("local.conf"))
    .map(res ⇒ ConfigFactory.parseURL(res).resolve)
    .getOrElse(ConfigFactory.empty())
  val BootstrapServersKey = "bootstrap.servers"
  val EmbeddedKafkaKey    = "embedded-kafka"

  /**
   * Starts the local runner using an Application Descriptor JSON file and
   * the file in the local system where the output is going to be written.
   *
   * @param args: args(0) must be the JSON-encoded Application Descriptor
   *             args(1) must be the file to use for the output
   */
  def main(args: Array[String]): Unit = {
    println("Starting local runner!! " + args.mkString(","))
    val usage = "Usage: localRunner <applicationFileJson> <outputFile>"
    val (appDescriptorFilename, outputFilename) = args.toList match {
      case app :: out :: Nil ⇒ (app, out)
      case Nil               ⇒ throw new RuntimeException(s"Missing application configuration file and output file for Local Runner\n$usage")
      case _ :: Nil          ⇒ throw new RuntimeException(s"Missing output file for Local Runner\n$usage")
      case _                 ⇒ throw new RuntimeException(s"Invalid parameters for Local Runner. \n$usage")
    }

    val outputFile = new File(outputFilename)
    require(outputFile.exists(), s"Output file [${outputFile}] must exist before starting this process")
    println("local runner - using output file: " + outputFile)

    val fos = new FileOutputStream(outputFile)
    JRuntime.getRuntime.addShutdownHook(shutdownHook(fos))

    Console.withOut(fos) {
      System.setOut(new PrintStream(fos))
      readDescriptorFile(appDescriptorFilename) match {
        case Success(applicationDescriptor) ⇒
          println("Running app descriptor")
          run(applicationDescriptor)
        case Failure(ex) ⇒
          println("Failed to run because" + ex.getMessage)
          log.error(s"Failed JSON unmarshalling of application descriptor file [${appDescriptorFilename}].", ex)
          System.exit(1)
      }
    }
  }

  private def run(appDescriptor: ApplicationDescriptor): Unit = {

    val kafkaPort            = 9093
    val bootstrapServers     = if (localConf.hasPath(BootstrapServersKey)) localConf.getString(BootstrapServersKey) else s"localhost:$kafkaPort"
    val embeddedKafkaEnabled = if (localConf.hasPath(EmbeddedKafkaKey)) localConf.getBoolean(EmbeddedKafkaKey) else true

    val appId      = appDescriptor.appId
    val appVersion = appDescriptor.appVersion
    val baseConfig = ConfigFactory.load()

    val streamletParameterConfig = resolveLocalStreamletConf(appDescriptor.streamlets).recoverWith {
      case missingConfEx: MissingConfigurationException ⇒
        log.error("Missing streamlet configuration: \n" + missingConfEx.keys.mkString("\n"))
        println("Missing streamlet configuration: \n" + missingConfEx.keys.mkString("\n"))
        log.error("Configuration for local running is resolved from local.conf in the application's classpath")
        Failure(missingConfEx)
    }.get

    val streamlets = appDescriptor.streamlets.sortBy(_.name)

    var endpointIdx = 0
    val streamletsWithConf = streamlets.map { streamletInstance ⇒
      val streamletName = streamletInstance.name
      println("Going to run streamlet: " + streamletName)
      // Make sure that we convert any backslash in the path to a forward slash since we want to store this in a JSON value
      val localStorageDirectory =
        Files.createTempDirectory(s"local-runner-storage-${streamletName}").toFile.getAbsolutePath.replace('\\', '/')
      log.info(s"Using local storage directory: $localStorageDirectory")

      val existingPortMappings =
        appDescriptor.deployments
          .find(_.streamletName == streamletInstance.name)
          .map(_.portMappings)
          .getOrElse(Map.empty[String, Topic])

      val deployment: StreamletDeployment =
        StreamletDeployment(appDescriptor.appId,
                            streamletInstance,
                            "",
                            existingPortMappings,
                            StreamletDeployment.EndpointContainerPort + endpointIdx)
      deployment.endpoint.foreach(_ => endpointIdx += 1)

      val runnerConfigObj      = RunnerConfig(appId, appVersion, deployment, bootstrapServers)
      val runnerConfig         = addStorageConfig(ConfigFactory.parseString(runnerConfigObj.data), localStorageDirectory)
      val streamletParamConfig = streamletParameterConfig.atPath("cloudflow.streamlets")

      val patchedRunnerConfig = runnerConfig
        .withFallback(streamletParamConfig)
        .withFallback(baseConfig)
        .withValue("cloudflow.local", ConfigValueFactory.fromAnyRef(true))
      (streamletInstance, patchedRunnerConfig)
    }

    val launchedStreamlets = streamletsWithConf.map {
      case (streamletDescriptor, config) ⇒
        loadStreamletClass(streamletDescriptor.descriptor.className)
          .map { streamlet ⇒
            log.info(s"Preparing streamlet: ${streamletDescriptor.name}")
            println(s"Preparing streamlet: ${streamletDescriptor.name}")
            streamlet.run(config)
          }
          .recoverWith {
            case NonFatal(ex) ⇒
              Failure(StreamletLaunchFailure(streamletDescriptor.name, ex))
          }
    }

    reportAndExitOnFailure(launchedStreamlets)

    val pipelineExecution = Future.sequence {
      launchedStreamlets.collect { case Success(streamletExecution) ⇒ streamletExecution.completed }
    }

    Await.ready(pipelineExecution, Duration.Inf).onComplete {
      case Success(_) ⇒
        log.info("Application terminated successfully")
        System.exit(0)
      case Failure(ex) ⇒ {
        log.error("Failure in streamlet execution", ex)
        ex.printStackTrace()
        System.exit(-1)
      }
    }

  }

  private def reportAndExitOnFailure(launchedStreamlets: Vector[Try[StreamletExecution]], exit: ⇒ Unit = System.exit(-1)): Unit = {
    val failed = launchedStreamlets.collect { case Failure(ex) ⇒ ex }
    if (failed.nonEmpty) {
      log.error("The application can't be started.")
      failed.foreach { ex ⇒
        log.error(ex.getMessage, ex)
        println("failure to launch streamlet" + ex.getMessage)
      }
      exit
    }
  }

  case class StreamletLaunchFailure(streamletName: String, failure: Throwable)
      extends Exception(s"Streamlet [$streamletName] failed to launch. Reason: ${failure.getMessage}", failure)

  private def resolveLocalStreamletConf(descriptors: Vector[StreamletInstance]): Try[Config] = {

    val streamletConfig: Seq[(String, String, Option[String])] = descriptors.flatMap { streamletDescriptor ⇒
      streamletDescriptor.descriptor.configParameters.map { configParamDescriptor ⇒
        val key            = s"${streamletDescriptor.name}.${configParamDescriptor.key}"
        val validationType = configParamDescriptor.validationType
        val value = if (localConf.hasPath(key)) {
          Some(localConf.getString(key))
        } else {
          configParamDescriptor.defaultValue
        }
        (key, validationType, value)
      }
    }

    val missingValues = streamletConfig.collect { case (k, _, None) ⇒ k }
    if (missingValues.nonEmpty) {
      Failure(MissingConfigurationException(missingValues))
    } else {
      Success {
        // I'll let the consumer of this configuration to parse the values as they want.
        // This quoting policy is here to preserve the type of the value in the resulting config obj
        ConfigFactory.parseString {
          streamletConfig
            .collect {
              case (key, validationType, Some(value)) ⇒
                s"$key : ${quotePolicy(validationType)(value)}"
            }
            .mkString("\n")
        }
      }
    }
  }

  private val isNonQuotedType = Set(BooleanValidationType.`type`, IntegerValidationType.`type`, DoubleValidationType.`type`)

  private def quotePolicy(validationType: String): String ⇒ String = { x ⇒
    if (isNonQuotedType(validationType)) x else s""""$x""""

  }

  private def readDescriptorFile(appDescriptorFilename: String): Try[ApplicationDescriptor] =
    Try {
      JsonParser(ParserInput(Files.readAllBytes(Paths.get(appDescriptorFilename))))
        .convertTo[ApplicationDescriptor]
    }.recoverWith {
      case NonFatal(ex) ⇒
        log.error(s"Failed to load application descriptor file [${appDescriptorFilename}].", ex)
        Failure(ex)
    }

  def withResourceDo[T <: Closeable](closeable: T)(f: T ⇒ Unit): Unit =
    try {
      f(closeable)
    } catch {
      case NonFatal(_) ⇒ ()
    } finally {
      try {
        closeable.close()
      } catch {
        case NonFatal(_) ⇒ ()
      }
    }

  final case class MissingConfigurationException(keys: Seq[String])
      extends Exception("Missing streamlet configuration(s): " + keys.mkString(","))
}
