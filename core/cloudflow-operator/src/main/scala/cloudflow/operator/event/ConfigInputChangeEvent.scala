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

/**
 * Indicates that the configuration of the application has been changed by the user.
 */
case class ConfigInputChangeEvent(appId: String, namespace: String, watchEvent: WatchEvent[Secret])

object ConfigInputChangeEvent {
  val SecretDataKey = "secret.conf"

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
                currentSecrets = currentSecrets + (absoluteName -> watchEvent)
                (for {
                  appId        ← metadata.labels.get(Operator.AppIdLabel)
                  configFormat <- metadata.labels.get(CloudflowLabels.ConfigFormat) if configFormat == CloudflowLabels.InputConfig
                  _ = system.log.info(s"[app: $appId configuration changed ${changeInfo(watchEvent)}]")
                } yield {
                  ConfigInputChangeEvent(appId, namespace, watchEvent)
                }).toList
              } else List()
          }
        }
      }

  def getData(secret: Secret): String =
    secret.data.get(ConfigInputChangeEvent.SecretDataKey).map(bytes => new String(bytes, StandardCharsets.UTF_8)).getOrElse("")

  private def changeInfo[T <: ObjectResource](watchEvent: WatchEvent[T]) = {
    val obj      = watchEvent._object
    val metadata = obj.metadata
    s"(${getKind(obj)} ${metadata.name} ${watchEvent._type})"
  }

  private def getKind(obj: ObjectResource) = if (obj.kind.isEmpty) obj.getClass.getSimpleName else obj.kind // sometimes kind is empty.

  /**
   * Finds the associated [[CloudflowApplication.CR]]s for [[ConfigInputChangeEvent]]s.
   * The resulting flow outputs tuples of the app and the streamlet change event.
   */
  def mapToAppInSameNamespace(
      client: KubernetesClient
  )(implicit ec: ExecutionContext): Flow[ConfigInputChangeEvent, (Option[CloudflowApplication.CR], ConfigInputChangeEvent), NotUsed] =
    Flow[ConfigInputChangeEvent].mapAsync(1) { configInputChangeEvent ⇒
      val ns = configInputChangeEvent.watchEvent._object.metadata.namespace
      client.usingNamespace(ns).getOption[CloudflowApplication.CR](configInputChangeEvent.appId).map { cr =>
        cr -> configInputChangeEvent
      }
    }

  def toInputConfigUpdateAction(
      implicit system: ActorSystem
  ): Flow[(Option[CloudflowApplication.CR], ConfigInputChangeEvent), Action[ObjectResource], NotUsed] =
    Flow[(Option[CloudflowApplication.CR], ConfigInputChangeEvent)]
      .map {
        case (Some(app), configInputChangeEvent) ⇒
          import configInputChangeEvent._
          val appConfig = getConfigFromSecret(configInputChangeEvent, system)

          app.spec.deployments.map { streamletDeployment ⇒
            val streamletName   = streamletDeployment.streamletName
            val runtimeConfig   = getStreamletRuntimeConfig(streamletDeployment.runtime, streamletName, appConfig)
            var streamletConfig = getStreamletConfig(streamletName, appConfig, runtimeConfig)

            streamletConfig = moveConfigParameters(streamletConfig, streamletName)
            streamletConfig = mergeRuntimeConfigToRoot(streamletConfig, streamletName)
            streamletConfig = removeKubernetesConfigSection(streamletConfig, streamletName)
            streamletConfig = moveTopicsConfigToPortMappings(app, streamletConfig, streamletName)

            // TODO get runtime config and create a separate runner secret for it.
            // TODO get kubernetes config and create a separate secret for it.

            // create update action for output secret action which is mounted as config by runtime specific deployments
            val outputSecret = Secret(
              metadata = ObjectMeta(
                name = streamletDeployment.secretName,
                namespace = app.metadata.namespace,
                labels =
                  CloudflowLabels(app).baseLabels ++ Map(
                        Operator.AppIdLabel          -> appId,
                        Operator.StreamletNameLabel  -> streamletName,
                        CloudflowLabels.ConfigFormat -> CloudflowLabels.OutputConfig
                      ),
                ownerReferences = CloudflowApplication.getOwnerReferences(app)
              ),
              data = Map(
                ConfigInputChangeEvent.SecretDataKey -> streamletConfig
                      .root()
                      .render(ConfigRenderOptions.concise())
                      .getBytes(StandardCharsets.UTF_8)
              )
            )
            Action.create(outputSecret, secretEditor)
          }

        case _ ⇒ Nil // app could not be found, do nothing.
      }
      .mapConcat(_.toList)

  def getConfigFromSecret(configInputChangeEvent: ConfigInputChangeEvent, system: ActorSystem) = {
    val secret: Secret = configInputChangeEvent.watchEvent._object
    secret.data
      .get(ConfigInputChangeEvent.SecretDataKey)
      .map { bytes =>
        Try(ConfigFactory.parseString(new String(bytes, StandardCharsets.UTF_8))).toOption.getOrElse {
          system.log.error(
            s"Detected input secret '${secret.metadata.name}' contains invalid configuration data, IGNORING configuration."
          )
          ConfigFactory.empty()
        }
      }
      .getOrElse {
        system.log.error(
          s"Detected input secret '${secret.metadata.name}' does not have data key '${ConfigInputChangeEvent.SecretDataKey}', IGNORING configuration."
        )
        ConfigFactory.empty()
      }
  }

  def streamletConfigPath(streamletName: String)        = s"cloudflow.streamlets.$streamletName"
  def streamletRuntimeConfigPath(streamletName: String) = s"cloudflow.streamlets.$streamletName.config"
  def globalRuntimeConfigPath(runtime: String)          = s"cloudflow.runtimes.$runtime.config"

  def getStreamletConfig(streamletName: String, appConfig: Config, runtimeConfig: Config) = {
    val path = streamletConfigPath(streamletName)

    Try(appConfig.getConfig(path)).toOption
      .getOrElse(ConfigFactory.empty())
      .atPath(path)
      .withFallback(runtimeConfig)
  }

  def getStreamletRuntimeConfig(runtime: String, streamletName: String, appConfig: Config) =
    Try(appConfig.getConfig(globalRuntimeConfigPath(runtime))).toOption
      .getOrElse(ConfigFactory.empty())
      .atPath(streamletRuntimeConfigPath(streamletName))

  def moveConfigParameters(config: Config, streamletName: String): Config = {
    val key                        = streamletConfigPath(streamletName)
    val configParametersKey        = "config-parameters"
    val absoluteConfigParameterKey = s"$key.$configParametersKey"
    val configParametersSection = Try(Some(config.getConfig(absoluteConfigParameterKey)))
      .getOrElse(None)

    configParametersSection
      .map { c =>
        val adjustedConfigParametersConfig = c.atPath(key)
        config.withoutPath(absoluteConfigParameterKey).withFallback(adjustedConfigParametersConfig)
      }
      .getOrElse(config)
  }

  def mergeRuntimeConfigToRoot(config: Config, streamletName: String): Config = {
    val streamletKey = streamletConfigPath(streamletName)

    val configKey                = "config"
    val absoluteRuntimeConfigKey = s"$streamletKey.$configKey"
    val runtimeConfigSection = Try(Some(config.getConfig(absoluteRuntimeConfigKey)))
      .getOrElse(None)
    // removing 'config' section and move it contents in the root of the config (akka, spark, flink, etc).
    runtimeConfigSection
      .map { c =>
        val configs = c
          .root()
          .entrySet()
          .asScala
          .map { entry =>
            entry.getValue.atPath(entry.getKey)
          }
          .toVector
        val mergedConfig = config.withoutPath(absoluteRuntimeConfigKey)
        configs.foldLeft(mergedConfig) { (acc, el) =>
          acc.withFallback(el)
        }
      }
      .getOrElse(config)
  }

  def removeKubernetesConfigSection(config: Config, streamletName: String): Config =
    // remove the kubernetes section, but it does need to be provided as Pod related details to the Runners that need to pass these through.
    // TODO implement
    config

  def moveTopicsConfigToPortMappings(app: CloudflowApplication.CR, config: Config, streamletName: String): Config =
    // convert to cloudflow.runner.streamlet.port_mappings.topic
    // TODO does merge work well on arrays? (port mappings / connected ports is an array, maybe change to 'map')
    // TODO implement
    config

  // val kubernetesKey               = "kubernetes"
  // val absoluteKubernetesConfigKey = s"$streamletKey.$kubernetesKey"
  // val topicsKey                   = "topics"
  // val absoluteTopicsConfigKey     = s"$streamletKey.$topicsKey"

  def secretEditor = new ObjectEditor[Secret] {
    def updateMetadata(obj: Secret, newMetadata: ObjectMeta): Secret = obj.copy(metadata = newMetadata)
  }
}
