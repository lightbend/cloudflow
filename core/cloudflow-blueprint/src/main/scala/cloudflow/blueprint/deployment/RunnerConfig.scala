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

package cloudflow.blueprint.deployment

import java.io.File

import com.typesafe.config._
import spray.json._

import cloudflow.blueprint.VolumeMountDescriptor

trait ConfigMapData {
  def filename: String
  def data: String
}

case class RunnerConfig(data: String) extends ConfigMapData {
  val filename: String = RunnerConfig.AppConfigFilename
}

object RunnerConfig extends DefaultJsonProtocol with ConfigJsonFormat {
  val PortMappingsPath     = "cloudflow.runner.streamlet.context.port_mappings"
  val AppConfigFilename    = "application.conf"
  implicit val topicFormat = jsonFormat(Topic.apply, "id", "config")

  def apply(
      appId: String,
      appVersion: String,
      deployment: StreamletDeployment,
      kafkaBootstrapServers: Option[String]
  ): RunnerConfig = {
    val map = Map("runner" -> toRunnerJson(appId, appVersion, deployment)) ++
          kafkaBootstrapServers.map(bs => Map("kafka" -> JsObject("bootstrap-servers" -> JsString(bs)))).getOrElse(Map())
    RunnerConfig(
      JsObject(
        "cloudflow" -> JsObject(map)
      ).compactPrint
    )
  }

  private def toRunnerJson(appId: String, appVersion: String, deployment: StreamletDeployment) = JsObject(
    "streamlet" -> JsObject(
          "class_name"    -> JsString(deployment.className),
          "streamlet_ref" -> JsString(deployment.streamletName),
          "context" -> JsObject(
                "app_id"        -> appId.toJson,
                "app_version"   -> appVersion.toJson,
                "config"        -> toJson(deployment.config),
                "volume_mounts" -> toVolumeMountJson(deployment.volumeMounts),
                "port_mappings" -> toPortMappingsJson(deployment.portMappings)
              )
        )
  )

  private def toJson(config: Config) = config.root().render(ConfigRenderOptions.concise()).parseJson

  private def toPortMappingsJson(portMappings: Map[String, Topic]) =
    JsObject(
      portMappings.map {
        case (portName, topic) ⇒ portName -> topic.toJson
      }
    )

  private def toVolumeMountJson(volumeMounts: Option[List[VolumeMountDescriptor]]) =
    JsArray(
      volumeMounts
        .getOrElse(Vector())
        .map {
          case VolumeMountDescriptor(name, path, accessMode, _) ⇒
            JsObject(
              "name"        -> JsString(name),
              "path"        -> JsString(path),
              "access_mode" -> JsString(accessMode)
            )
        }
        .toVector
    )

}

case class PrometheusConfig(data: String) extends ConfigMapData {
  val filename: String = PrometheusConfig.PrometheusConfigFilename
}

object PrometheusConfig extends DefaultJsonProtocol {
  val PrometheusConfigFilename               = "prometheus.yaml"
  val PrometheusJmxExporterPort              = 2050
  def prometheusConfigPath(basePath: String) = basePath + File.separator + PrometheusConfig.PrometheusConfigFilename
}
