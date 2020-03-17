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

import cloudflow.streamlets.{ Codec, DecodeException }
import scalapb.{ GeneratedMessage, GeneratedMessageCompanion }
import scalapb.descriptors.Descriptor

import scala.util.{ Failure, Try }

class ProtobufCodec[T <: GeneratedMessage: GeneratedMessageCompanion] extends Codec[T] {
  val companion = implicitly[GeneratedMessageCompanion[T]]

  def encode(protoMsg: T): Array[Byte] = companion.toByteArray(protoMsg)

  def decode(protoMsgBytes: Array[Byte]): T =
    Try {
      companion.parseFrom(protoMsgBytes)
    }.recoverWith {
      case err => Failure(DecodeException("Could not decode Protobuf message", err))
    }.get

  def pbDescriptor: Descriptor = companion.scalaDescriptor
}
