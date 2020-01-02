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

package cloudflow.akkastream

import java.nio.file.{ Paths, Files }
import java.nio.charset.StandardCharsets

import cloudflow.streamlets._
import BootstrapInfo._

import cloudflow.streamlets.StreamletRuntime
import com.typesafe.config._
import scala.util.{ Try, Failure }
import net.ceedubs.ficus.Ficus._

/**
 * Extend from this class to build Akka-based Streamlets.
 */
abstract class AkkaStreamlet extends Streamlet[AkkaStreamletContext] {
  final override val runtime = AkkaStreamletRuntime

  /**
   * Initialize the streamlet from the config. In some cases (e.g. the tests) we may pass a context
   * directly to be used instead of building it from the config.
   */
  override protected final def createContext(config: Config): AkkaStreamletContext = {
    (for {
      streamletDefinition ← StreamletDefinition.read(config)
    } yield {
      val updatedConfig = streamletDefinition.copy(config = streamletDefinition.config.withFallback(config))
      AkkaStreamletContextImpl(updatedConfig)
    })
      .recoverWith {
        case th ⇒ Failure(new Exception(s"Failed to create context from $config", th))
      }.get
  }

  override final def run(context: AkkaStreamletContext): StreamletExecution = {

    // readiness probe to be done at operator using this
    // the streamlet context has been created and the streamlet is ready to take requests
    // needs to be done only in cluster mode - not in local running

    val localMode = context.config.as[Option[Boolean]]("cloudflow.local").getOrElse(false)
    if (!localMode) createTempFile(s"${context.streamletRef}-ready.txt", context.streamletRef)

    val blockingIODispatcherConfig = context.system.settings.config.getConfig("akka.actor.default-blocking-io-dispatcher")
    val dispatcherConfig = context.system.settings.config.getConfig("akka.actor.default-dispatcher")
    val deploymentConfig = context.system.settings.config.getConfig("akka.actor.deployment")
    val streamletConfig = Try {
      context.system.settings.config.getConfig("cloudflow.runner.streamlets")
    }.getOrElse(ConfigFactory.empty())

    context.system.log.info(startRunnerMessage(blockingIODispatcherConfig, dispatcherConfig, deploymentConfig, streamletConfig))

    val logic = createLogic()

    // create a marker file indicating that the streamlet has started running
    // this will be used for pod liveness probe
    // needs to be done only in cluster mode - not in local running

    if (!localMode) createTempFile(s"${context.streamletRef}-live.txt", context.streamletRef)

    logic.run()
    signalReadyAfterStart()
    context.streamletExecution
  }

  private def createTempFile(relativePath: String, streamletRef: String): Unit = {
    val tempDir = System.getProperty("java.io.tmpdir")
    val path = java.nio.file.Paths.get(tempDir, relativePath)

    Files.write(
      Paths.get(path.toString),
      s"an akka streamlet $streamletRef".getBytes(StandardCharsets.UTF_8))
  }

  override def logStartRunnerMessage(buildInfo: String): Unit = {
    log.info(s"""
      |Initializing Akkastream Runner ..
      |\n${box("Build Info")}
      |${buildInfo}
      """.stripMargin
    )
  }

  /**
   * Implement this method to define the logic that this streamlet should execute once it is run.
   */
  protected def createLogic(): AkkaStreamletLogic

  private def readyAfterStart(): Boolean = if (attributes.contains(ServerAttribute)) false else true

  private def signalReadyAfterStart(): Unit = {
    if (readyAfterStart) context.signalReady
  }
}

final case object AkkaStreamletRuntime extends StreamletRuntime {
  override val name: String = "akka"
}
