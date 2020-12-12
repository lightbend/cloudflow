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

package cloudflow.streamlets.bytearray

import cloudflow.streamlets._

case class ExternalInlet(
    name: String,
    hasUniqueGroupId: Boolean = false,
    errorHandler: (Array[Byte], Throwable) => Option[Array[Byte]] = CodecInlet.logAndSkip[Array[Byte]](_: Array[Byte], _: Throwable)
) extends CodecInlet[Array[Byte]] {
  def codec                                                                                              = new ByteArrayCodec
  def schemaDefinition                                                                                   = ByteArrayUtil.createSchemaDefinition()
  def schemaAsString                                                                                     = ByteArrayUtil.schemaName
  def withUniqueGroupId: ExternalInlet                                                                   = copy(hasUniqueGroupId = true)
  override def withErrorHandler(handler: (Array[Byte], Throwable) => Option[Array[Byte]]): ExternalInlet = copy(errorHandler = handler)
}

object ExternalInlet {
  // Java API
  def create(name: String): ExternalInlet = ExternalInlet(name)

  // Java API
  def create(name: String, hasUniqueGroupId: Boolean): ExternalInlet = ExternalInlet(name, hasUniqueGroupId)
}
