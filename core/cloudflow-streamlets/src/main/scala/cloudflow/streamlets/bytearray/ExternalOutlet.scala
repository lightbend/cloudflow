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

package cloudflow.streamlets.bytearray

import cloudflow.streamlets._

final case class ExternalOutlet(name: String, partitioner: Array[Byte] => String = RoundRobinPartitioner)
    extends CodecOutlet[Array[Byte]] {
  val codec = ByteArrayCodec
  def schemaDefinition = ByteArrayUtil.createSchemaDefinition()
  def schemaAsString = ByteArrayUtil.schemaName

  /**
   * Returns a CodecOutlet with the partitioner set.
   */
  def withPartitioner(partitioner: Array[Byte] => String): ExternalOutlet = copy(partitioner = partitioner)
}
