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

package cloudflow.blueprint.deployment

import java.io.File

trait ConfigMapData {
  def filename: String
  def data: String
}

object RunnerConfig {
  val PortMappingsPath = "cloudflow.runner.streamlet.context.port_mappings"
}

case class PrometheusConfig(data: String) extends ConfigMapData {
  val filename: String = PrometheusConfig.PrometheusConfigFilename
}

object PrometheusConfig {
  val PrometheusConfigFilename = "prometheus.yaml"
  val PrometheusJmxExporterPort = 2050
  def prometheusConfigPath(basePath: String) = basePath + File.separator + PrometheusConfig.PrometheusConfigFilename
}
