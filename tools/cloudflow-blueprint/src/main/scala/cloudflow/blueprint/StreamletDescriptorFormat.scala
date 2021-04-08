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

package cloudflow.blueprint

import spray.json._

object StreamletDescriptorFormat extends StreamletDescriptorFormat

trait StreamletDescriptorFormat extends DefaultJsonProtocol {
  implicit val attributeFormat = jsonFormat(StreamletAttributeDescriptor.apply, "attribute_name", "config_path")
  implicit val schemaFormat    = jsonFormat4(SchemaDescriptor.apply)
  implicit val inletFormat     = jsonFormat2(InletDescriptor.apply)
  implicit val outletFormat    = jsonFormat2(OutletDescriptor.apply)
  implicit val configParameterDescriptorFormat =
    jsonFormat(ConfigParameterDescriptor.apply, "key", "description", "validation_type", "validation_pattern", "default_value")
  implicit val volumeMountDescriptorFormat =
    jsonFormat(VolumeMountDescriptor.apply, "name", "path", "access_mode", "pvc_name")

  implicit val streamletRuntimeFormat = new JsonFormat[StreamletRuntimeDescriptor] {
    def write(runtime: StreamletRuntimeDescriptor) = JsString(runtime.name)
    def read(json: JsValue) =
      json match {
        case JsString(name) => StreamletRuntimeDescriptor(name)
        case str            => deserializationError("Expected StreamletRuntimeDescriptor as JsString, but got " + str)
      }
  }

  implicit val streamletDescriptorFormat = jsonFormat(StreamletDescriptor.apply,
                                                      "class_name",
                                                      "runtime",
                                                      "labels",
                                                      "description",
                                                      "inlets",
                                                      "outlets",
                                                      "config_parameters",
                                                      "attributes",
                                                      "volume_mounts")
}
