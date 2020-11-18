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

import scala.util.{ Failure, Success, Try }

import scala.reflect.ClassTag
import scala.reflect._
import org.apache.avro.specific.SpecificRecordBase
import org.apache.avro.Schema
import cloudflow.streamlets._

object AvroUtil {
  val Format = "avro"

  def makeSchema[T <: SpecificRecordBase: ClassTag]: Schema =
    Try(classTag[T].runtimeClass.getDeclaredMethod("SCHEMA$")) match {
      case Success(schema) => schema.invoke(null).asInstanceOf[Schema]
      case Failure(_) => {
        Try(classTag[T].runtimeClass.getDeclaredField("SCHEMA$")) match {
          case Success(schema) => schema.get(null).asInstanceOf[Schema]
          case Failure(ex)     => throw new RuntimeException(s"Error fetching avro schema for class ${classTag[T].runtimeClass}", ex)
        }
      }
    }
  def fingerprintSha256(schema: Schema): String = {
    import java.util.Base64

    import org.apache.avro.SchemaNormalization._

    Base64
      .getEncoder()
      .encodeToString(parsingFingerprint("SHA-256", schema))
  }

  def createSchemaDefinition(schema: Schema) = SchemaDefinition(
    name = schema.getFullName,
    schema = schema.toString(false),
    fingerprint = fingerprintSha256(schema),
    format = Format
  )
}
