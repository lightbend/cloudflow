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
import scala.collection.JavaConverters._
import scala.concurrent._
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

  def getData(secret: Secret): String =
    secret.data.get(ConfigInputChangeEvent.SecretDataKey).map(bytes => new String(bytes, StandardCharsets.UTF_8)).getOrElse("")

  def toInputConfigUpdateAction(
      implicit system: ActorSystem
  ): Flow[(Option[CloudflowApplication.CR], ConfigInputChangeEvent), Action[ObjectResource], NotUsed] =
    Flow[(Option[CloudflowApplication.CR], ConfigInputChangeEvent)]
      .map {
        case (Some(app), configInputChangeEvent) ⇒
          val appConfig = getConfigFromSecret(configInputChangeEvent, system)

          app.spec.deployments.flatMap { streamletDeployment ⇒
            val streamletName    = streamletDeployment.streamletName
            val runtime          = streamletDeployment.runtime
            val runtimeConfig    = getGlobalRuntimeConfigAtStreamletPath(runtime, streamletName, appConfig)
            val kubernetesConfig = getGlobalKubernetesConfigAtStreamletPath(runtime, streamletName, appConfig)
            var streamletConfig  = getMergedStreamletConfig(streamletName, appConfig, runtimeConfig, kubernetesConfig)

            streamletConfig = moveConfigParameters(streamletConfig, streamletName)
            streamletConfig = mergeRuntimeConfigToRoot(streamletConfig, streamletName)
            streamletConfig = mergeKubernetesConfigToRoot(streamletConfig, streamletName)

            val mergedKubernetesConfig = Try(streamletConfig.getConfig(KubernetesKey).atPath(KubernetesKey)).getOrElse(ConfigFactory.empty)
            val mergedRuntimeConfig    = Try(streamletConfig.getConfig(runtime).atPath(runtime)).getOrElse(ConfigFactory.empty)

            streamletConfig = moveTopicsConfigToPortMappings(streamletDeployment, streamletConfig, appConfig)

            // create update action for output secret action which is mounted as config by runtime specific deployments
            val configSecret =
              createSecret(
                streamletDeployment.secretName,
                app,
                streamletDeployment,
                streamletConfig,
                mergedRuntimeConfig,
                mergedKubernetesConfig,
                CloudflowLabels.StreamletDeploymentConfigFormat
              )
            List(Action.createOrUpdate(configSecret, secretEditor))
          }

        case _ ⇒ Nil // app could not be found, do nothing.
      }
      .mapConcat(_.toList)

  def getConfigFromSecret(configInputChangeEvent: ConfigInputChangeEvent, system: ActorSystem) = {
    val secret: Secret = configInputChangeEvent.watchEvent._object
    val empty          = ConfigFactory.empty()
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
            empty
        }.toOption
      }
      .getOrElse {
        system.log.error(
          s"Detected input secret '${secret.metadata.name}' does not have data key '${ConfigInputChangeEvent.SecretDataKey}', IGNORING configuration."
        )
        empty
      }
  }

  val TopicsConfigPath                                  = "cloudflow.topics"
  def streamletConfigPath(streamletName: String)        = s"cloudflow.streamlets.$streamletName"
  val KubernetesKey                                     = "kubernetes"
  def streamletRuntimeConfigPath(streamletName: String) = s"cloudflow.streamlets.$streamletName.config"
  def globalRuntimeConfigPath(runtime: String)          = s"cloudflow.runtimes.$runtime.config"

  def streamletKubernetesConfigPath(streamletName: String) = s"cloudflow.streamlets.$streamletName.$KubernetesKey"
  def globalKubernetesConfigPath(runtime: String)          = s"cloudflow.runtimes.$runtime.$KubernetesKey"

  def getMergedStreamletConfig(streamletName: String, appConfig: Config, runtimeConfig: Config, kubernetesConfig: Config) = {
    val path = streamletConfigPath(streamletName)

    Try(appConfig.getConfig(path)).toOption
      .getOrElse(ConfigFactory.empty())
      .atPath(path)
      .withFallback(runtimeConfig)
      .withFallback(kubernetesConfig)
  }

  def getGlobalRuntimeConfigAtStreamletPath(runtime: String, streamletName: String, appConfig: Config) =
    Try(appConfig.getConfig(globalRuntimeConfigPath(runtime))).toOption
      .getOrElse(ConfigFactory.empty())
      .atPath(streamletRuntimeConfigPath(streamletName))

  def getGlobalKubernetesConfigAtStreamletPath(runtime: String, streamletName: String, appConfig: Config) =
    Try(appConfig.getConfig(globalKubernetesConfigPath(runtime))).toOption
      .getOrElse(ConfigFactory.empty())
      .atPath(streamletKubernetesConfigPath(streamletName))

  def moveConfigParameters(config: Config, streamletName: String): Config = {
    val key                        = streamletConfigPath(streamletName)
    val configParametersKey        = "config-parameters"
    val absoluteConfigParameterKey = s"$key.$configParametersKey"
    val configParametersSection    = Try(config.getConfig(absoluteConfigParameterKey)).toOption

    configParametersSection
      .map { c =>
        val adjustedConfigParametersConfig = c.atPath(key)
        config.withoutPath(absoluteConfigParameterKey).withFallback(adjustedConfigParametersConfig)
      }
      .getOrElse(config)
  }

  def mergeRuntimeConfigToRoot(streamletConfig: Config, streamletName: String): Config =
    mergeConfigToRoot(streamletConfig, streamletName, "config", false)

  def mergeKubernetesConfigToRoot(streamletConfig: Config, streamletName: String): Config =
    mergeConfigToRoot(streamletConfig, streamletName, "kubernetes", true)

  def mergeConfigToRoot(streamletConfig: Config, streamletName: String, configKey: String, prefixWithConfigKey: Boolean = false): Config = {
    val streamletKey = streamletConfigPath(streamletName)

    val absoluteConfigKey = s"$streamletKey.$configKey"
    val configSection     = Try(streamletConfig.getConfig(absoluteConfigKey)).toOption
    // removing section and move its contents in the root.
    configSection
      .map { c =>
        val configs = c
          .root()
          .entrySet()
          .asScala
          .map { entry =>
            val key =
              if (prefixWithConfigKey) s"$configKey.${entry.getKey}"
              else entry.getKey
            entry.getValue.atPath(key)
          }
          .toVector
        val mergedConfig = streamletConfig.withoutPath(absoluteConfigKey)
        configs.foldLeft(mergedConfig) { (acc, el) =>
          acc.withFallback(el)
        }
      }
      .getOrElse(streamletConfig)
  }

  /*
   * Moves cloudflow.topics.<topic> config to cloudflow.runner.streamlet.context.port_mappings.<port>.config.
   * The runner merges the secret on top of the configmap, which brings everything together.
   */
  def moveTopicsConfigToPortMappings(deployment: StreamletDeployment, streamletConfig: Config, appConfig: Config): Config = {
    val portMappingConfigs = deployment.portMappings.flatMap {
      case (port, topic) =>
        Try(
          appConfig
            .getConfig(s"$TopicsConfigPath.${topic.id}")
            .withFallback(topic.config)
            .atPath(s"cloudflow.runner.streamlet.context.port_mappings.$port.config")
            // Need to retain the topic.id
            .withFallback(ConfigFactory.parseString(s"""
                cloudflow.runner.streamlet.context.port_mappings.$port.id = ${topic.id}
              """))
        ).toOption
    }
    portMappingConfigs.foldLeft(streamletConfig) { (acc, el) =>
      acc.withFallback(el)
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
  ) =
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
        ConfigInputChangeEvent.SecretDataKey        -> getData(config),
        ConfigInputChangeEvent.RuntimeConfigDataKey -> getData(runtimeConfig),
        ConfigInputChangeEvent.PodsConfigDataKey    -> getData(podsConfig)
      )
    )
  private def getData(config: Config) =
    config
      .root()
      .render(ConfigRenderOptions.concise())
      .getBytes(StandardCharsets.UTF_8)

  def secretEditor = new ObjectEditor[Secret] {
    def updateMetadata(obj: Secret, newMetadata: ObjectMeta): Secret = obj.copy(metadata = newMetadata)
  }
}
