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

import scala.collection.immutable

object StreamletDescriptor {
  private val Server = "server"
}

final case class StreamletDescriptor(className: String,
                                     runtime: StreamletRuntimeDescriptor,
                                     labels: immutable.IndexedSeq[String],
                                     description: String,
                                     inlets: immutable.IndexedSeq[InletDescriptor],
                                     outlets: immutable.IndexedSeq[OutletDescriptor],
                                     configParameters: immutable.IndexedSeq[ConfigParameterDescriptor],
                                     attributes: immutable.IndexedSeq[StreamletAttributeDescriptor] = Vector.empty,
                                     volumeMounts: immutable.IndexedSeq[VolumeMountDescriptor]) {
  def isIngress: Boolean = inlets.isEmpty && outlets.nonEmpty
  def isServer: Boolean  = attributes.exists(_.attributeName == StreamletDescriptor.Server)
  def getAttribute(name: String): Option[StreamletAttributeDescriptor] = attributes.find { attrib =>
    attrib.attributeName == name
  }
}

case class StreamletRuntimeDescriptor(name: String) {
  override def toString: String = name
}
sealed trait PortDescriptor {
  def name: String
  def schema: SchemaDescriptor
  def isOutlet: Boolean
}

final case class InletDescriptor(name: String, schema: SchemaDescriptor) extends PortDescriptor {
  def isOutlet = false
}

final case class OutletDescriptor(name: String, schema: SchemaDescriptor) extends PortDescriptor {
  def isOutlet = true
}

final case class SchemaDescriptor(name: String, schema: String, fingerprint: String, format: String)

final case class StreamletAttributeDescriptor(attributeName: String, configPath: String)

final case class ConfigParameterDescriptor(key: String,
                                           description: String,
                                           validationType: String,
                                           validationPattern: Option[String],
                                           defaultValue: Option[String])

final case class VolumeMountDescriptor(
    name: String,
    path: String,
    accessMode: String,
    pvcName: String = "" // This string is only used in the operator and will remain empty until deserialized on the operator side
)
