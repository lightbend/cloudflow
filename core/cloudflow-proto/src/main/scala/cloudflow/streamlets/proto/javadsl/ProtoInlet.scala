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

package cloudflow.streamlets.proto.javadsl
import java.util.Optional
import cloudflow.streamlets._
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.{ GeneratedMessageV3, TextFormat }

final case class ProtoInlet[T <: GeneratedMessageV3](
    name: String,
    clazz: Class[T],
    hasUniqueGroupId: Boolean = false,
    errorHandler: (Array[Byte], Throwable) => Option[T] = CodecInlet.logAndSkip[T](_: Array[Byte], _: Throwable))
    extends CodecInlet[T] {
  // We know we can do this because of 'GeneratedMessageV3'
  val descriptor = clazz.getMethod("getDescriptor").invoke(null).asInstanceOf[Descriptor]

  val codec = new ProtoCodec[T](clazz)
  def schemaAsString = TextFormat.printer.escapingNonAscii(false).printToString(descriptor.toProto)
  def schemaDefinition = ProtoUtil.createSchemaDefinition(descriptor)

  def withUniqueGroupId: ProtoInlet[T] = if (hasUniqueGroupId) this else copy(hasUniqueGroupId = true)
  override def withErrorHandler(handler: (Array[Byte], Throwable) => Option[T]): CodecInlet[T] =
    copy(errorHandler = handler)
}

object ProtoInlet {

  /**
   * Creates a ProtoInlet.
   * @param name The name of the inlet
   * @param clazz The type of message that can be consumed from this inlet
   */
  def create[T <: GeneratedMessageV3](name: String, clazz: Class[T]): ProtoInlet[T] =
    ProtoInlet[T](name, clazz)

  /**
   * Creates a ProtoInlet.
   * @param name The name of the inlet
   * @param clazz The type of message that can be consumed from this inlet
   * @param hasUniqueGroupId Specifies if the inlet should have a unique group Id across streamlet instances.
   *        Setting hasUniqueGroupId to true, when a streamlet is scaled, will result in the inlet to receive all messsages of the topic (similar to a broadcast).
   *        Setting hasUniqueGroupId to false, when a streamlet is scaled, will result in the inlet to receive only part of the messages of the topic (partitioned).
   *
   */
  def create[T <: GeneratedMessageV3](name: String, clazz: Class[T], hasUniqueGroupId: Boolean): ProtoInlet[T] =
    ProtoInlet[T](name, clazz, hasUniqueGroupId)

  /**
   * Creates a ProtoInlet.
   * @param name The name of the inlet
   * @param clazz The type of message that can be consumed from this inlet
   * @param hasUniqueGroupId Specifies if the inlet should have a unique group Id across streamlet instances.
   *        Setting hasUniqueGroupId to true, when a streamlet is scaled, will result in the inlet to receive all messsages of the topic (similar to a broadcast).
   *        Setting hasUniqueGroupId to false, when a streamlet is scaled, will result in the inlet to receive only part of the messages of the topic (partitioned).
   * @param errorHandler The errorHandler, which is invoked when a message could not be deserialized.
   */
  def create[T <: GeneratedMessageV3](
      name: String,
      clazz: Class[T],
      hasUniqueGroupId: Boolean,
      errorHandler: (Array[Byte], Throwable) => Optional[T]): ProtoInlet[T] =
    ProtoInlet[T](name, clazz, hasUniqueGroupId, (a, t) => {
      val opt = errorHandler(a, t)
      if (opt.isPresent) Some(opt.get()) else None
    })
}
