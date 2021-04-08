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

import com.typesafe.config._
import spray.json._

// TODO: eventually this trait should be moved to the deployment package
//       It is currently being used for a serialization trick that was
//       necessary when we were cross-compiling Spark for 2.11 but that
//       is no longer an issue.
import cloudflow.blueprint.StreamletDescriptorFormat

trait ConfigJsonFormat extends DefaultJsonProtocol {
  implicit val configFormat = new RootJsonFormat[Config] {
    def read(json: JsValue)            = ConfigFactory.parseString(json.compactPrint)
    def write(config: Config): JsValue = config.root().render(ConfigRenderOptions.concise()).parseJson
  }
}

trait ApplicationDescriptorJsonFormat extends StreamletDescriptorFormat with ConfigJsonFormat {
  implicit val streamletFormat = jsonFormat(StreamletInstance.apply, "name", "descriptor")

  implicit val topicFormat    = jsonFormat(Topic.apply, "id", "cluster", "config")
  implicit val endpointFormat = jsonFormat(Endpoint.apply, "app_id", "streamlet", "container_port")

  implicit val streamletDeploymentFormat = jsonFormat(
    StreamletDeployment.apply,
    "name",
    "runtime",
    "image",
    "streamlet_name",
    "class_name",
    "endpoint",
    "secret_name",
    "config",
    "port_mappings",
    "volume_mounts",
    "replicas"
  )

  implicit val applicationDescriptorFormat = jsonFormat(ApplicationDescriptor.apply,
                                                        "app_id",
                                                        "app_version",
                                                        "streamlets",
                                                        "deployments",
                                                        "agent_paths",
                                                        "version",
                                                        "library_version")
}

object ApplicationDescriptorJsonFormat extends ApplicationDescriptorJsonFormat
