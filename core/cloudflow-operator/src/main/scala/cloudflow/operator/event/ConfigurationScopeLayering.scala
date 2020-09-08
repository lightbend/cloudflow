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

package cloudflow.operator.event

import cloudflow.blueprint.deployment.StreamletDeployment
import com.typesafe.config.{ Config, ConfigFactory }
import scala.collection.JavaConverters._

import scala.util.Try

/**
 * Implementation of https://cloudflow.io/docs/current/develop/cloudflow-configuration.html
 */
object ConfigurationScopeLayering {

  def configs(streamletDeployment: StreamletDeployment, appConfig: Config): (Config, Config, Config) = {
    val streamletName = streamletDeployment.streamletName
    val runtime       = streamletDeployment.runtime

    val streamletConfig = mergeToStreamletConfig(runtime, streamletName, appConfig)
    val podsConfig      = extractPodsConfig(streamletConfig)
    val runtimeConfig   = extractRuntimeConfig(runtime, streamletConfig)

    val asPortMappings = ConfigurationScopeLayering.moveTopicsConfigToPortMappings(streamletDeployment, streamletConfig, appConfig)
    (asPortMappings, runtimeConfig, podsConfig)
  }

  // open for unit testing
  private[event] def mergeToStreamletConfig(runtime: String, streamletName: String, appConfig: Config): Config = {
    val runtimeConfig    = getGlobalRuntimeConfigAtStreamletPath(runtime, streamletName, appConfig)
    val kubernetesConfig = getGlobalKubernetesConfigAtStreamletPath(runtime, streamletName, appConfig)
    var streamletConfig  = getMergedStreamletConfig(streamletName, appConfig, runtimeConfig, kubernetesConfig)
    streamletConfig = moveConfigParameters(streamletConfig, streamletName)
    streamletConfig = mergeConfigToRoot(streamletConfig, streamletName, "config", prefixWithConfigKey = false)
    mergeConfigToRoot(streamletConfig, streamletName, "kubernetes", prefixWithConfigKey = true)
  }

  private val TopicsConfigPath                                  = "cloudflow.topics"
  private def streamletConfigPath(streamletName: String)        = s"cloudflow.streamlets.$streamletName"
  private val KubernetesKey                                     = "kubernetes"
  private def streamletRuntimeConfigPath(streamletName: String) = s"cloudflow.streamlets.$streamletName.config"
  private def globalRuntimeConfigPath(runtime: String)          = s"cloudflow.runtimes.$runtime.config"

  private def streamletKubernetesConfigPath(streamletName: String) = s"cloudflow.streamlets.$streamletName.$KubernetesKey"
  private def globalKubernetesConfigPath(runtime: String)          = s"cloudflow.runtimes.$runtime.$KubernetesKey"

  private def getMergedStreamletConfig(streamletName: String, appConfig: Config, runtimeConfig: Config, kubernetesConfig: Config) = {
    val path = streamletConfigPath(streamletName)

    Try(appConfig.getConfig(path)).toOption
      .getOrElse(ConfigFactory.empty())
      .atPath(path)
      .withFallback(runtimeConfig)
      .withFallback(kubernetesConfig)
  }

  private def getGlobalRuntimeConfigAtStreamletPath(runtime: String, streamletName: String, appConfig: Config) =
    Try(appConfig.getConfig(globalRuntimeConfigPath(runtime))).toOption
      .getOrElse(ConfigFactory.empty())
      .atPath(streamletRuntimeConfigPath(streamletName))

  private def getGlobalKubernetesConfigAtStreamletPath(runtime: String, streamletName: String, appConfig: Config) =
    Try(appConfig.getConfig(globalKubernetesConfigPath(runtime))).toOption
      .getOrElse(ConfigFactory.empty())
      .atPath(streamletKubernetesConfigPath(streamletName))

  private def moveConfigParameters(config: Config, streamletName: String): Config = {
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

  private def mergeConfigToRoot(streamletConfig: Config, streamletName: String, configKey: String, prefixWithConfigKey: Boolean): Config = {
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
  private def moveTopicsConfigToPortMappings(deployment: StreamletDeployment, streamletConfig: Config, appConfig: Config): Config = {
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

  private def extractPodsConfig(streamletConfig: Config) =
    Try(streamletConfig.getConfig(KubernetesKey).atPath(KubernetesKey)).getOrElse(ConfigFactory.empty)

  private def extractRuntimeConfig(runtime: String, streamletConfig: Config) =
    Try(streamletConfig.getConfig(runtime).atPath(runtime)).getOrElse(ConfigFactory.empty)
}
