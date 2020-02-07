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

import cloudflow.streamlets._
import org.apache.avro.specific.SpecificRecordBase

import scala.reflect.ClassTag
import scala.reflect._

import AvroUtil._

case class AvroInlet[T <: SpecificRecordBase: ClassTag](name: String, hasUniqueGroupId: Boolean = false) extends CodecInlet[T] {
  def codec                           = new AvroCodec[T](makeSchema)
  def schemaDefinition                = createSchemaDefinition(makeSchema)
  def schemaAsString                  = makeSchema.toString(false)
  def withUniqueGroupId: AvroInlet[T] = copy(hasUniqueGroupId = true)
}

object AvroInlet {
  // Java API
  def create[T <: SpecificRecordBase](name: String, clazz: Class[T]): AvroInlet[T] =
    AvroInlet[T](name)(ClassTag.apply(clazz))

  def create[T <: SpecificRecordBase](name: String, clazz: Class[T], hasUniqueGroupId: Boolean): AvroInlet[T] =
    AvroInlet[T](name, hasUniqueGroupId)(ClassTag.apply(clazz))
}
