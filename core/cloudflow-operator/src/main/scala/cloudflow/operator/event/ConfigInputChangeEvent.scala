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

package cloudflow.operator
package event

import java.nio.charset.StandardCharsets

import scala.util.Try
import akka.actor._
import akka.NotUsed
import akka.stream.scaladsl._
import com.typesafe.config._
import skuber._
import skuber.api.client._
import skuber.json.format._
import cloudflow.operator.action._
import cloudflow.blueprint.deployment.StreamletDeployment

/**
 * Indicates that the configuration of the application has been changed by the user.
 */
case class ConfigInputChangeEvent(appId: String, namespace: String, watchEvent: WatchEvent[Secret]) extends AppChangeEvent[Secret]

object ConfigInputChangeEvent extends Event {

  val SecretDataKey        = "secret.conf"
  val RuntimeConfigDataKey = "runtime-config.conf"
  val PodsConfigDataKey    = "pods-config.conf"

  /** log message for when a ConfigInputChangeEvent is identified as a configuration change event */
  def detected[T <: ObjectResource](event: ConfigInputChangeEvent) =
    s"User created or modified configuration for application ${event.appId}."

  /**
   * Transforms [[skuber.api.client.WatchEvent]]s into [[ConfigInputChangeEvent]]s.
   */
  def fromWatchEvent()(implicit system: ActorSystem): Flow[WatchEvent[Secret], ConfigInputChangeEvent, NotUsed] =
    Flow[WatchEvent[Secret]]
      .statefulMapConcat { () ⇒
        var currentSecrets = Map[String, WatchEvent[Secret]]()
        watchEvent ⇒ {
          val secret       = watchEvent._object
          val metadata     = secret.metadata
          val secretName   = secret.metadata.name
          val namespace    = secret.metadata.namespace
          val absoluteName = s"$namespace.$secretName"

          def hasChanged(existingEvent: WatchEvent[Secret]) =
            watchEvent._object.resourceVersion != existingEvent._object.resourceVersion && getData(existingEvent._object) != getData(secret)

          watchEvent._type match {
            case EventType.DELETED ⇒
              currentSecrets = currentSecrets - absoluteName
              List()
            case EventType.ADDED | EventType.MODIFIED ⇒
              if (currentSecrets.get(absoluteName).forall(hasChanged)) {
                (for {
                  appId        ← metadata.labels.get(Operator.AppIdLabel)
                  configFormat <- metadata.labels.get(CloudflowLabels.ConfigFormat) if configFormat == CloudflowLabels.InputConfig
                  _ = system.log.info(s"[app: $appId application configuration changed ${changeInfo(watchEvent)}]")
                } yield {
                  currentSecrets = currentSecrets + (absoluteName -> watchEvent)
                  ConfigInputChangeEvent(appId, namespace, watchEvent)
                }).toList
              } else List()
          }
        }
      }

  def getConfigFromSecret(secret: Secret): Config = {
    val str = getData(secret)
    ConfigFactory.parseString(str)
  }

  def getData(secret: Secret): String =
    secret.data.get(ConfigInputChangeEvent.SecretDataKey).map(bytes => new String(bytes, StandardCharsets.UTF_8)).getOrElse("")

  def toInputConfigUpdateAction(
      implicit system: ActorSystem,
      ctx: DeploymentContext
  ): Flow[(Option[CloudflowApplication.CR], ConfigInputChangeEvent), Action[ObjectResource], NotUsed] =
    Flow[(Option[CloudflowApplication.CR], ConfigInputChangeEvent)]
      .map {
        case (Some(app), configInputChangeEvent) ⇒
          val appConfig = getConfigFromSecret(configInputChangeEvent, system)

          val clusterNames = (for {
              d          <- app.spec.deployments
              (_, topic) <- d.portMappings
              cluster    <- topic.cluster.toVector
            } yield cluster) :+ TopicActions.DefaultConfigurationName

          val providedAction = Action.providedByLabel[Secret, Secret](
            TopicActions.KafkaClusterNameLabel,
            clusterNames,
            ctx.podNamespace, { clusterSecrets =>
              val allNamedClusters = namedClusters(app.name, clusterNames, clusterSecrets, system)
              val actions = app.spec.deployments.map {
                streamletDeployment ⇒
                  val configs = ConfigurationScopeLayering.configs(streamletDeployment, appConfig, allNamedClusters)

                  // create update action for output secret action which is mounted as config by runtime specific deployments
                  val configSecret =
                    createSecret(
                      streamletDeployment.secretName,
                      app,
                      streamletDeployment,
                      configs.streamlet,
                      configs.runtime,
                      configs.pods,
                      CloudflowLabels.StreamletDeploymentConfigFormat
                    )
                  Action.createOrUpdate(configSecret, secretEditor)
              }

              Action.composite(actions)
            }
          )
          List(providedAction)
        case _ ⇒ Nil // app could not be found, do nothing.
      }
      .mapConcat(_.toList)

  /**
   * Look up all Kafka cluster names referenced in streamlet definitions with the named cluster secrets found in K8s.
   * If a referenced cluster name does not have a corresponding secret K8s then log an error, but continue.
   * Return all named cluster configs and the 'default' cluster config, if one is defined.
   */
  def namedClusters(appName: String, clusterNames: Vector[String], clusterSecrets: ListResource[Secret], system: ActorSystem) = {
    val namedClusters: Map[String, Config] = clusterNames.flatMap { name =>
      val secret = clusterSecrets.items.find(_.metadata.labels.get(TopicActions.KafkaClusterNameLabel).contains(name))
      secret match {
        case Some(secret) =>
          val clusterConfigStr = getConfigFromSecret(secret)
          Vector(name -> clusterConfigStr)
        case None =>
          system.log.error(
            s"""
              |The referenced cluster configuration secret '$name' for app '$appName' does not exist.
              |This should have been detected at deploy time.
              |This will lead to a runtime exception when the streamlet runs.
            """.stripMargin
          )
          Nil
      }
    }.toMap

    val maybeDefaultCluster = clusterSecrets.items
      .find(_.metadata.labels.get(TopicActions.KafkaClusterNameLabel).contains(TopicActions.DefaultConfigurationName))
      .map(s => TopicActions.DefaultConfigurationName -> getConfigFromSecret(s))
      .toMap

    namedClusters ++ maybeDefaultCluster
  }

  def getConfigFromSecret(configInputChangeEvent: ConfigInputChangeEvent, system: ActorSystem) = {
    val secret: Secret = configInputChangeEvent.watchEvent._object
    secret.data
      .get(ConfigInputChangeEvent.SecretDataKey)
      .flatMap { bytes =>
        val str = new String(bytes, StandardCharsets.UTF_8)
        Try(ConfigFactory.parseString(str).resolve()).recover {
          case cause =>
            system.log.error(
              cause,
              s"Detected input secret '${secret.metadata.name}' contains invalid configuration data, IGNORING configuration."
            )
            ConfigFactory.empty()
        }.toOption
      }
      .getOrElse {
        system.log.error(
          s"Detected input secret '${secret.metadata.name}' does not have data key '${ConfigInputChangeEvent.SecretDataKey}', IGNORING configuration."
        )
        ConfigFactory.empty()
      }
  }

  def createSecret(
      secretName: String,
      app: CloudflowApplication.CR,
      streamletDeployment: StreamletDeployment,
      config: Config,
      runtimeConfig: Config,
      podsConfig: Config,
      configFormat: String
  ) = {
    def render(config: Config): Array[Byte] =
      config
        .root()
        .render(ConfigRenderOptions.concise())
        .getBytes(StandardCharsets.UTF_8)

    Secret(
      metadata = ObjectMeta(
        name = secretName,
        namespace = app.metadata.namespace,
        labels =
          CloudflowLabels(app).baseLabels ++ Map(
                Operator.AppIdLabel          -> app.spec.appId,
                Operator.StreamletNameLabel  -> streamletDeployment.streamletName,
                CloudflowLabels.ConfigFormat -> configFormat
              ),
        ownerReferences = CloudflowApplication.getOwnerReferences(app)
      ),
      data = Map(
        ConfigInputChangeEvent.SecretDataKey        -> render(config),
        ConfigInputChangeEvent.RuntimeConfigDataKey -> render(runtimeConfig),
        ConfigInputChangeEvent.PodsConfigDataKey    -> render(podsConfig)
      )
    )
  }

  def secretEditor = new ObjectEditor[Secret] {
    def updateMetadata(obj: Secret, newMetadata: ObjectMeta): Secret = obj.copy(metadata = newMetadata)
  }
}
