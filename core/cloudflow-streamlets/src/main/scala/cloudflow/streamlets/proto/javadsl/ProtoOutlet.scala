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

package cloudflow.streamlets.proto.javadsl

import cloudflow.streamlets._
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.{ GeneratedMessageV3, TextFormat }

final case class ProtoOutlet[T <: GeneratedMessageV3](
    name: String,
    partitioner: T => String,
    clazz: Class[T]
) extends CodecOutlet[T] {
  // We know we can do this because of 'GeneratedMessageV3'
  val descriptor = clazz.getMethod("getDescriptor").invoke(null).asInstanceOf[Descriptor]

  override def codec: Codec[T]  = new ProtoCodec(clazz)
  override def schemaAsString   = TextFormat.printer.escapingNonAscii(false).printToString(descriptor.toProto)
  override def schemaDefinition = ProtoUtil.createSchemaDefinition(descriptor)

  /**
   * Returns a CodecOutlet with the partitioner set.
   */
  override def withPartitioner(partitioner: T => String): ProtoOutlet[T] = copy(partitioner = partitioner)
}
