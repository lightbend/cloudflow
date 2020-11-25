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

import scala.util.Try

final case class ProtoInlet[T <: GeneratedMessageV3](name: String,
                                                     clazz: Class[T],
                                                     hasUniqueGroupId: Boolean = false,
                                                     var errorHandler: (Array[Byte], Try[T]) => Option[T] =
                                                       LoggingErrorHandler.processException(_: Array[Byte], _: Try[T]))
    extends CodecInlet[T] {
  // We know we can do this because of 'GeneratedMessageV3'
  val descriptor = clazz.getMethod("getDescriptor").invoke(null).asInstanceOf[Descriptor]

  val codec            = new ProtoCodec[T](clazz)
  def schemaAsString   = TextFormat.printToUnicodeString(descriptor.toProto)
  def schemaDefinition = ProtoUtil.createSchemaDefinition(descriptor)

  def withUniqueGroupId: ProtoInlet[T] = if (hasUniqueGroupId) this else copy(hasUniqueGroupId = true)
  override def withErrorHandler(handler: (Array[Byte], Try[T]) => Option[T]): CodecInlet[T] = {
    errorHandler = handler
    this
  }
  override def handleErrors(message: Array[Byte], result: Try[T]): Option[T] = errorHandler(message, result)
}
