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

import scala.util.{Success, Try}

class ByteArrayCodec extends Codec[Array[Byte]] {
  override def encode(value: Array[Byte]): Array[Byte] = value

  override def decode(value: Array[Byte]): Try[Array[Byte]] = Success(value)
}