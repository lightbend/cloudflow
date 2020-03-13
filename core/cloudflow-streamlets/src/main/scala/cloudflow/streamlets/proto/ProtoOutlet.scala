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

package cloudflow.streamlets.proto

import cloudflow.streamlets._

import scalapb.{ GeneratedMessage, GeneratedMessageCompanion }

final case class ProtoOutlet[T <: GeneratedMessage: GeneratedMessageCompanion](
    name: String,
    partitioner: T ⇒ String
) extends CodecOutlet[T] {
  val cmp              = implicitly[GeneratedMessageCompanion[T]]
  val codec            = new ProtoCodec[T]
  def schemaAsString   = cmp.scalaDescriptor.asProto.toProtoString
  def schemaDefinition = ProtoUtil.createSchemaDefinition(cmp.scalaDescriptor)

  /**
   * Returns a CodecOutlet with the partitioner set.
   */
  def withPartitioner(partitioner: T ⇒ String): ProtoOutlet[T] = copy(partitioner = partitioner)
}
