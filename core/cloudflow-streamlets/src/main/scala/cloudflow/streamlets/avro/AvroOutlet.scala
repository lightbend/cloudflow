/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

import cloudflow.streamlets._
import org.apache.avro.specific.SpecificRecordBase

import scala.reflect.ClassTag
import scala.reflect._

import AvroUtil._

case class AvroOutlet[T <: SpecificRecordBase: ClassTag](name: String, partitioner: T ⇒ String = RoundRobinPartitioner) extends CodecOutlet[T] {
  def codec = new AvroCodec[T](makeSchema)
  def schemaDefinition = createSchemaDefinition(makeSchema)
  def schemaAsString = makeSchema.toString(false)
  /**
   * Returns a CodecOutlet with the partitioner set.
   */
  def withPartitioner(partitioner: T ⇒ String): AvroOutlet[T] = copy(partitioner = partitioner)
}

object AvroOutlet {
  // Java API
  def create[T <: SpecificRecordBase](name: String, partitioner: T ⇒ String, clazz: Class[T]): AvroOutlet[T] =
    AvroOutlet[T](name, partitioner)(ClassTag.apply(clazz))

  def create[T <: SpecificRecordBase](name: String, clazz: Class[T]): AvroOutlet[T] =
    AvroOutlet[T](name, RoundRobinPartitioner)(ClassTag.apply(clazz))
}
