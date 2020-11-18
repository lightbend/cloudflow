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
import com.typesafe.config._

import org.slf4j.LoggerFactory

import skuber.{ ListResource, ObjectEditor, ObjectMeta, ObjectResource, Secret }
import skuber.api.client.{ EventType, WatchEvent }
import skuber.json.format.secretFmt

import cloudflow.operator.action._
import cloudflow.blueprint.deployment.StreamletDeployment

/**
 * Indicates that the configuration of the application has been changed by the user.
 */
case class ConfigInputChangeEvent(appId: String, namespace: String, watchEvent: WatchEvent[Secret]) extends AppChangeEvent[Secret]

object ConfigInputChangeEvent extends Event {
  val log = LoggerFactory.getLogger(this.getClass)

  def toConfigInputChangeEvent(
      currentSecrets: Map[String, WatchEvent[Secret]],
      watchEvent: WatchEvent[Secret]
  ): (Map[String, WatchEvent[Secret]], List[ConfigInputChangeEvent]) = {
    val secret       = watchEvent._object
    val metadata     = secret.metadata
    val secretName   = secret.metadata.name
    val namespace    = secret.metadata.namespace
    val absoluteName = s"$namespace.$secretName"

    def hasChanged(existingEvent: WatchEvent[Secret]) =
      watchEvent._object.resourceVersion != existingEvent._object.resourceVersion && getData(existingEvent._object) != getData(secret)

    watchEvent._type match {
      case EventType.DELETED =>
        (currentSecrets - absoluteName, List())
      case EventType.ADDED | EventType.MODIFIED =>
        if (currentSecrets.get(absoluteName).forall(hasChanged)) {
          (for {
            appId        <- metadata.labels.get(CloudflowLabels.AppIdLabel)
            configFormat <- metadata.labels.get(CloudflowLabels.ConfigFormat) if configFormat == CloudflowLabels.InputConfig
            _ = log.info(s"[app: $appId application configuration changed ${changeInfo(watchEvent)}]")
          } yield {
            (currentSecrets + (absoluteName -> watchEvent), List(ConfigInputChangeEvent(appId, namespace, watchEvent)))
          }).getOrElse((currentSecrets, List()))

        } else (currentSecrets, List())
    }
  }

  def toActionList(mappedApp: Option[CloudflowApplication.CR], event: ConfigInputChangeEvent, podNamespace: String): Seq[Action] =
    (mappedApp, event) match {
      case (Some(app), configInputChangeEvent) =>
        val appConfig = getConfigFromSecret(configInputChangeEvent)

        val clusterNames = (for {
            d          <- app.spec.deployments
            (_, topic) <- d.portMappings
            cluster    <- topic.cluster.toVector
          } yield cluster) :+ TopicActions.DefaultConfigurationName

        val providedAction = Action.providedByLabel[Secret, Secret](TopicActions.KafkaClusterNameLabel, clusterNames, app, podNamespace) {
          clusterSecrets =>
            val allNamedClusters = namedClusters(app.name, clusterNames, clusterSecrets)
            val actions = app.spec.deployments.map { streamletDeployment =>
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
              Action.createOrUpdate(configSecret, app, secretEditor)
            }

            Action.composite(actions, app, app.metadata.namespace)
        }

        List(providedAction)
      case _ => Nil // app could not be found, do nothing.
    }

  val SecretDataKey        = "secret.conf"
  val RuntimeConfigDataKey = "runtime-config.conf"
  val PodsConfigDataKey    = "pods-config.conf"

  /** log message for when a ConfigInputChangeEvent is identified as a configuration change event */
  def detected[T <: ObjectResource](event: ConfigInputChangeEvent) =
    s"User created or modified configuration for application ${event.appId}."

  def getConfigFromSecret(secret: Secret): Config = {
    val str = getData(secret)
    ConfigFactory.parseString(str)
  }

  def getData(secret: Secret): String =
    secret.data.get(ConfigInputChangeEvent.SecretDataKey).map(bytes => new String(bytes, StandardCharsets.UTF_8)).getOrElse("")

  /**
   * Look up all Kafka cluster names referenced in streamlet definitions with the named cluster secrets found in K8s.
   * If a referenced cluster name does not have a corresponding secret K8s then log an error, but continue.
   * Return all named cluster configs and the 'default' cluster config, if one is defined.
   */
  def namedClusters(appName: String, clusterNames: Vector[String], clusterSecrets: ListResource[Secret]) = {
    val namedClusters: Map[String, Config] = clusterNames.flatMap { name =>
      val secret = clusterSecrets.items.find(_.metadata.labels.get(TopicActions.KafkaClusterNameLabel).contains(name))
      secret match {
        case Some(secret) =>
          val clusterConfigStr = getConfigFromSecret(secret)
          Vector(name -> clusterConfigStr)
        case None =>
          log.error(
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

  def getConfigFromSecret(configInputChangeEvent: ConfigInputChangeEvent) = {
    val secret: Secret = configInputChangeEvent.watchEvent._object
    secret.data
      .get(ConfigInputChangeEvent.SecretDataKey)
      .flatMap { bytes =>
        val str = new String(bytes, StandardCharsets.UTF_8)
        Try(ConfigFactory.parseString(str).resolve()).recover {
          case cause =>
            log.error(
              s"Detected input secret '${secret.metadata.name}' contains invalid configuration data, IGNORING configuration.",
              cause
            )
            ConfigFactory.empty()
        }.toOption
      }
      .getOrElse {
        log.error(
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
                CloudflowLabels.AppIdLabel         -> app.spec.appId,
                CloudflowLabels.StreamletNameLabel -> streamletDeployment.streamletName,
                CloudflowLabels.ConfigFormat       -> configFormat
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
