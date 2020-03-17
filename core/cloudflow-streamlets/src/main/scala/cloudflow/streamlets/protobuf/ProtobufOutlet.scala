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

package cloudflow.streamlets.protobuf

import cloudflow.streamlets.{ CodecOutlet, RoundRobinPartitioner }
import cloudflow.streamlets.protobuf.PbScalaSupport.createSchemaDefinition
import scalapb.{ GeneratedMessage, GeneratedMessageCompanion }

case class ProtobufOutlet[T <: GeneratedMessage: GeneratedMessageCompanion](name: String, partitioner: T => String = RoundRobinPartitioner)
    extends CodecOutlet[T] {
  def codec            = new ProtobufCodec[T]
  def schemaAsString   = PbScalaSupport.schemaString(codec.pbDescriptor)
  def schemaDefinition = createSchemaDefinition(codec.pbDescriptor)

  def withPartitioner(partitioner: T => String): ProtobufOutlet[T] = copy(partitioner = partitioner)
}
