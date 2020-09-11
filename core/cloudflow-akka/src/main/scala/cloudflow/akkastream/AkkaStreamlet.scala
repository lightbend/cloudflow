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

import java.nio.file.{ Files, Paths }
import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.discovery.Discovery
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import cloudflow.streamlets._
import BootstrapInfo._
import cloudflow.streamlets.StreamletRuntime
import com.typesafe.config._

import scala.util.Failure
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
  override protected final def createContext(config: Config): AkkaStreamletContext =
    (for {
      streamletDefinition ← StreamletDefinition.read(config)
    } yield {

      val localMode = config.as[Option[Boolean]]("cloudflow.local").getOrElse(false)
      val updatedStreamletDefinition = streamletDefinition.copy(
        config = streamletDefinition.config
          .withFallback(ConfigFactory.parseResourcesAnySyntax("akka.conf"))
          .withFallback(config)
      )

      if (activateCluster && localMode) {
        val clusterConfig = ConfigFactory.parseResourcesAnySyntax("akka-cluster-local.conf")
        val fullConfig    = clusterConfig.withFallback(updatedStreamletDefinition.config)

        val system  = ActorSystem(streamletDefinition.streamletRef, ConfigFactory.load(fullConfig))
        val cluster = Cluster(system)
        cluster.join(cluster.selfAddress)

        new AkkaStreamletContextImpl(updatedStreamletDefinition, system)
      } else if (activateCluster) {
        val clusterConfig = ConfigFactory
          .parseString(
            s"""akka.discovery.kubernetes-api.pod-label-selector = "com.lightbend.cloudflow/streamlet-name=${streamletDefinition.streamletRef}""""
          )
          .withFallback(ConfigFactory.parseResourcesAnySyntax("akka-cluster-k8.conf"))

        val fullConfig = clusterConfig.withFallback(updatedStreamletDefinition.config)

        val system = ActorSystem(streamletDefinition.streamletRef, ConfigFactory.load(fullConfig))
        AkkaManagement(system).start()
        ClusterBootstrap(system).start()
        Discovery(system).loadServiceDiscovery("kubernetes-api")

        new AkkaStreamletContextImpl(updatedStreamletDefinition, system)
      } else {
        val system = ActorSystem(streamletDefinition.streamletRef, updatedStreamletDefinition.config)
        new AkkaStreamletContextImpl(updatedStreamletDefinition, system)
      }
    }).recoverWith {
      case th ⇒ Failure(new Exception(s"Failed to create context from $config", th))
    }.get

  override final def run(context: AkkaStreamletContext): StreamletExecution =
    try {
      // readiness probe to be done at operator using this
      // the streamlet context has been created and the streamlet is ready to take requests
      // needs to be done only in cluster mode - not in local running

      val localMode = context.config.as[Option[Boolean]]("cloudflow.local").getOrElse(false)
      if (!localMode) createTempFile(s"${context.streamletRef}-ready.txt", context.streamletRef)

      val logic = createLogic()

      // create a marker file indicating that the streamlet has started running
      // this will be used for pod liveness probe
      // needs to be done only in cluster mode - not in local running

      if (!localMode) createTempFile(s"${context.streamletRef}-live.txt", context.streamletRef)

      logic.run()
      signalReadyAfterStart()
      context.streamletExecution
    } catch {
      case e: Throwable =>
        // TODO fix this for failure.
        context.streamletExecution.stop()
        throw e
    }

  private def createTempFile(relativePath: String, streamletRef: String): Unit = {
    val tempDir = System.getProperty("java.io.tmpdir")
    val path    = java.nio.file.Paths.get(tempDir, relativePath)

    Files.write(Paths.get(path.toString), s"an akka streamlet $streamletRef".getBytes(StandardCharsets.UTF_8))
  }

  override def logStartRunnerMessage(buildInfo: String): Unit =
    log.info(s"""
      |Initializing Akkastream Runner ..
      |\n${box("Build Info")}
      |${buildInfo}
      """.stripMargin)

  /**
   * Implement this method to define the logic that this streamlet should execute once it is run.
   */
  protected def createLogic(): AkkaStreamletLogic

  private def readyAfterStart(): Boolean = !attributes.contains(ServerAttribute)

  private val activateCluster: Boolean = attributes.contains(AkkaClusterAttribute)

  private def signalReadyAfterStart(): Unit =
    if (readyAfterStart) context.signalReady
}

final case object AkkaStreamletRuntime extends StreamletRuntime {
  override val name: String = "akka"
}
