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

package cloudflow.streamlets.descriptors

import cloudflow.streamlets._
import spray.json._

final case class VolumeMountDescriptor(
    name: String,
    path: String,
    accessMode: String,
    pvcName: String = "" // This string is only used in the operator and will remain empty until deserialized on the operator side
)

final case class ConfigParameterDescriptor private (
    key: String,
    description: String,
    validationType: String,
    validationPattern: Option[String],
    defaultValue: Option[String]
)

object ConfigParameterDescriptor {
  def apply[T](key: String, description: String, validationType: ValidationType, defaultValue: Option[T]): ConfigParameterDescriptor =
    ConfigParameterDescriptor(key, description, validationType.`type`, validationType.pattern, defaultValue.map(_.toString()))
  // Java API
  def create(key: String, description: String, validationType: ValidationType) =
    ConfigParameterDescriptor(key, description, validationType.`type`, validationType.pattern, Some(""))
  def create(key: String, description: String, validationType: ValidationType, defaultValue: String) =
    ConfigParameterDescriptor(key, description, validationType.`type`, validationType.pattern, Some(defaultValue))

}

object StreamletDescriptor extends DefaultJsonProtocol {

  def jsonDescriptor[Context <: StreamletContext](streamlet: Streamlet[Context]): String =
    streamlet.toJson(streamletWriter.asInstanceOf[JsonWriter[Streamlet[Context]]]).compactPrint

  implicit val volumeMountDescriptorFormat: RootJsonFormat[VolumeMountDescriptor] =
    jsonFormat(VolumeMountDescriptor.apply, "name", "path", "access_mode", "pvc_name")

  implicit final class VolumeMountToDescriptor(val volumeMount: VolumeMount) extends AnyVal {
    def toDescriptor = VolumeMountDescriptor(volumeMount.name, volumeMount.path, volumeMount.accessMode.toString)
  }

  implicit val configParameterDescriptorFormat: RootJsonFormat[ConfigParameterDescriptor] =
    jsonFormat(ConfigParameterDescriptor.apply, "key", "description", "validation_type", "validation_pattern", "default_value")

  implicit final class ConfigParameterDescriptorToDescriptor(val configParameterDescriptor: ConfigParameterDescriptor) extends AnyVal {
    def toDescriptor = ConfigParameterDescriptor(
      configParameterDescriptor.key,
      configParameterDescriptor.description,
      configParameterDescriptor.validationType,
      configParameterDescriptor.validationPattern,
      configParameterDescriptor.defaultValue
    )
  }

  final case class StreamletAttributeDescriptor(attributeName: String, configPath: String)
  object StreamletAttributeDescriptor {
    implicit final class StreamletAttributeToDescriptor(val streamletAttribute: StreamletAttribute) extends AnyVal {
      def toDescriptor = StreamletAttributeDescriptor(streamletAttribute.attributeName, streamletAttribute.configPath)
    }
    implicit val attributeFormat = jsonFormat(StreamletAttributeDescriptor.apply, "attribute_name", "config_path")
  }

  final case class SchemaDescriptor(
      name: String,
      schema: String,
      fingerprint: String,
      format: String
  )

  final case class StreamletPortDescriptor(name: String, schemaDefinition: SchemaDescriptor)

  object StreamletPortDescriptor {
    implicit final class StreamletPortToDescriptor(val streamletPort: StreamletPort) extends AnyVal {
      def toDescriptor: StreamletPortDescriptor = {
        val sd               = streamletPort.schemaDefinition
        val schemaDescriptor = SchemaDescriptor(sd.name, sd.schema, sd.fingerprint, sd.format)
        StreamletPortDescriptor(streamletPort.name, schemaDescriptor)
      }
    }

    implicit val streamletPortDescriptorWriter: JsonFormat[StreamletPortDescriptor] =
      lift(new JsonWriter[StreamletPortDescriptor] {
        def write(port: StreamletPortDescriptor): JsValue =
          JsObject(
            "name" -> JsString(port.name),
            "schema" -> JsObject(
                  "name"        -> JsString(port.schemaDefinition.name),
                  "schema"      -> JsString(port.schemaDefinition.schema),
                  "fingerprint" -> JsString(port.schemaDefinition.fingerprint),
                  "format"      -> JsString(port.schemaDefinition.format)
                )
          )
      })
  }

  implicit val streamletWriter: JsonFormat[Streamlet[_ <: StreamletContext]] =
    lift(new JsonWriter[Streamlet[_ <: StreamletContext]] {
      def write(streamlet: Streamlet[_ <: StreamletContext]): JsValue = {
        import StreamletAttributeDescriptor._
        import StreamletPortDescriptor._
        val canonicalName = streamlet.getClass.getCanonicalName
        require(canonicalName != null, s"The Streamlet class ${streamlet.getClass} cannot be an anonymous class")

        JsObject(
          "class_name"        -> JsString(streamlet.getClass.getCanonicalName),
          "runtime"           -> JsString(streamlet.runtime.name),
          "labels"            -> JsArray(streamlet.labels.sorted.map(_.toJson).toVector),
          "description"       -> JsString(streamlet.description),
          "inlets"            -> JsArray(streamlet.inlets.map(_.toDescriptor).sortBy(_.name).map(_.toJson).toVector),
          "outlets"           -> JsArray(streamlet.outlets.map(_.toDescriptor).sortBy(_.name).map(_.toJson).toVector),
          "config_parameters" -> JsArray(streamlet.configParameters.map(_.toDescriptor).sortBy(_.key).map(_.toJson).toVector),
          "volume_mounts"     -> JsArray(streamlet.volumeMounts.map(_.toDescriptor).map(_.toJson).toVector),
          "attributes"        -> JsArray(streamlet.attributes.map(_.toDescriptor).map(_.toJson).toVector)
        )
      }
    })

}
