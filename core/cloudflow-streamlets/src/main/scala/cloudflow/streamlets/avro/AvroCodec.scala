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

package cloudflow.streamlets.avro

import scala.util._

import com.twitter.bijection.Injection
import org.apache.avro.Schema
import org.apache.avro.specific.SpecificRecordBase
import com.twitter.bijection.avro.SpecificAvroCodecs

import cloudflow.streamlets._

class AvroCodec[T <: SpecificRecordBase](avroSchema: Schema) extends Codec[T] {

  val recordInjection: Injection[T, Array[Byte]] = SpecificAvroCodecs.toBinary(avroSchema)
  val avroSerde                                  = new AvroSerde(recordInjection)

  def encode(value: T): Array[Byte]      = avroSerde.encode(value)
  def decode(bytes: Array[Byte]): Try[T] = avroSerde.decode(bytes)
  def schema: Schema                     = avroSchema
}

private[avro] class AvroSerde[T <: SpecificRecordBase](injection: Injection[T, Array[Byte]]) extends Serializable {
  val inverted: Array[Byte] => Try[T] = injection.invert _

  def encode(value: T): Array[Byte] = injection(value)

  // TODO fix up the exception, maybe pas through input
  def decode(bytes: Array[Byte]): Try[T] = inverted(bytes)
}
