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
  val AppConfigFilename        = "application.conf"
  implicit val savepointFormat = jsonFormat(Savepoint.apply, "app_id", "streamlet_ref", "name", "config", "create")

  def apply(
      appId: String,
      appVersion: String,
      deployment: StreamletDeployment,
      kafkaBootstrapServers: String
  ): RunnerConfig = apply(appId, appVersion, Vector(deployment), kafkaBootstrapServers)

  def apply(
      appId: String,
      appVersion: String,
      deployments: Vector[StreamletDeployment],
      kafkaBootstrapServers: String
  ): RunnerConfig =
    RunnerConfig(
      JsObject(
        "cloudflow" -> JsObject(
              "kafka"  -> JsObject("bootstrap-servers" -> JsString(kafkaBootstrapServers)),
              "runner" -> toRunnerJson(appId, appVersion, deployments)
            )
      ).compactPrint
    )

  private def toRunnerJson(appId: String, appVersion: String, deployments: Vector[StreamletDeployment]) = JsObject(
    "streamlets" -> JsArray(
          deployments.map { deployment ⇒
            JsObject(
              "class_name"    -> JsString(deployment.className),
              "streamlet_ref" -> JsString(deployment.streamletName),
              "context" -> JsObject(
                    "app_id"          -> appId.toJson,
                    "app_version"     -> appVersion.toJson,
                    "config"          -> toJson(deployment.config),
                    "volume_mounts"   -> toVolumeMountJson(deployment.volumeMounts),
                    "connected_ports" -> toConnectedPortsJson(deployment.portMappings)
                  )
            )
          }
        )
  )

  private def toJson(config: Config) = config.root().render(ConfigRenderOptions.concise()).parseJson

  private def toConnectedPortsJson(portMappings: Map[String, Savepoint]) =
    JsArray(
      portMappings.map {
        case (portName, savepoint) ⇒
          JsObject(
            "port"           -> JsString(portName),
            "savepoint_path" -> savepoint.toJson
          )
      }.toVector
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
