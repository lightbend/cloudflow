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

package cloudflow.akkastream

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

import scala.util.control.NonFatal

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
      streamletDefinition <- StreamletDefinition.read(config)
    } yield {

      val localMode = config.as[Option[Boolean]]("cloudflow.local").getOrElse(false)
      val updatedStreamletDefinition = streamletDefinition.copy(config = streamletDefinition.config
        .withFallback(ConfigFactory.parseResourcesAnySyntax("akka.conf"))
        .withFallback(config))

      if (activateCluster && localMode) {
        val clusterConfig = ConfigFactory.parseResourcesAnySyntax("akka-cluster-local.conf")
        val fullConfig = clusterConfig.withFallback(updatedStreamletDefinition.config)

        val system = ActorSystem(streamletDefinition.streamletRef, ConfigFactory.load(fullConfig))
        val factory = new AkkaStreamletContextFactory(system)

        val cluster = Cluster(system)
        cluster.join(cluster.selfAddress)

        factory.newContext(updatedStreamletDefinition)
      } else if (activateCluster) {
        val clusterConfig = ConfigFactory
          .parseString(
            s"""akka.discovery.kubernetes-api.pod-label-selector = "com.lightbend.cloudflow/streamlet-name=${streamletDefinition.streamletRef}"""")
          .withFallback(ConfigFactory.parseResourcesAnySyntax("akka-cluster-k8.conf"))

        val fullConfig = clusterConfig.withFallback(updatedStreamletDefinition.config)

        val system = ActorSystem(streamletDefinition.streamletRef, ConfigFactory.load(fullConfig))
        val factory = new AkkaStreamletContextFactory(system)

        AkkaManagement(system).start()
        ClusterBootstrap(system).start()
        Discovery(system).loadServiceDiscovery("kubernetes-api")

        factory.newContext(updatedStreamletDefinition)
      } else {
        val system = ActorSystem(streamletDefinition.streamletRef, updatedStreamletDefinition.config)
        val factory = new AkkaStreamletContextFactory(system)

        factory.newContext(updatedStreamletDefinition)
      }
    }).recoverWith {
      case th => Failure(new Exception(s"Failed to create context from $config", th))
    }.get

  override final def run(context: AkkaStreamletContext): StreamletExecution =
    try {
      val localMode = context.config.as[Option[Boolean]]("cloudflow.local").getOrElse(false)
      context.ready(localMode)

      val logic = createLogic()

      context.alive(localMode)
      logic.run()
      signalReadyAfterStart()
      context.streamletExecution
    } catch {
      case NonFatal(e) =>
        context.stopOnException(e)
        throw e
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
