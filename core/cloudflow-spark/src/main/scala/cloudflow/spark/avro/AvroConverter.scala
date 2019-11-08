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

// Function extracted from original work: com.databricks.spark.avro.AvroOutputWriter

package cloudflow.spark.avro

import java.nio.ByteBuffer
import java.sql.{ Date, Timestamp }
import java.util.HashMap

import org.apache.avro.{ Schema, SchemaBuilder }
import org.apache.avro.generic.GenericData.Record
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._

import scala.collection.immutable.Map

object AvroConverter {

  /**
   * This function constructs converter function for a given sparkSQL datatype. This is used in
   * writing Avro records out to disk
   */
  def createConverterToAvro(
      dataType: DataType,
      structName: String,
      recordNamespace: String): (Any) ⇒ Any = {
    dataType match {
      case BinaryType ⇒ (item: Any) ⇒ item match {
        case null               ⇒ null
        case bytes: Array[Byte] ⇒ ByteBuffer.wrap(bytes)
      }
      case ByteType | ShortType | IntegerType | LongType |
        FloatType | DoubleType | StringType | BooleanType ⇒ identity
      case _: DecimalType ⇒ (item: Any) ⇒ if (item == null) null else item.toString
      case TimestampType ⇒ (item: Any) ⇒
        if (item == null) null else item.asInstanceOf[Timestamp].getTime
      case DateType ⇒ (item: Any) ⇒
        if (item == null) null else item.asInstanceOf[Date].getTime
      case ArrayType(elementType, _) ⇒
        val elementConverter = createConverterToAvro(
          elementType,
          structName,
          SchemaConverters.getNewRecordNamespace(elementType, recordNamespace, structName))
        (item: Any) ⇒ {
          if (item == null) {
            null
          } else {
            val sourceArray = item.asInstanceOf[Seq[Any]]
            val sourceArraySize = sourceArray.size
            val targetList = new java.util.ArrayList[Any](sourceArraySize)
            var idx = 0
            while (idx < sourceArraySize) {
              targetList.add(elementConverter(sourceArray(idx)))
              idx += 1
            }
            targetList
          }
        }
      case MapType(StringType, valueType, _) ⇒
        val valueConverter = createConverterToAvro(
          valueType,
          structName,
          SchemaConverters.getNewRecordNamespace(valueType, recordNamespace, structName))
        (item: Any) ⇒ {
          if (item == null) {
            null
          } else {
            val javaMap = new HashMap[String, Any]()
            item.asInstanceOf[Map[String, Any]].foreach {
              case (key, value) ⇒
                javaMap.put(key, valueConverter(value))
            }
            javaMap
          }
        }
      case structType: StructType ⇒
        val builder = SchemaBuilder.record(structName).namespace(recordNamespace)
        val schema: Schema = SchemaConverters.convertStructToAvro(
          structType, builder, recordNamespace)
        val fieldConverters = structType.fields.map(field ⇒
          createConverterToAvro(
            field.dataType,
            field.name,
            SchemaConverters.getNewRecordNamespace(field.dataType, recordNamespace, field.name)))
        (item: Any) ⇒ {
          if (item == null) {
            null
          } else {
            val record = new Record(schema)
            val convertersIterator = fieldConverters.iterator
            val fieldNamesIterator = dataType.asInstanceOf[StructType].fieldNames.iterator
            val rowIterator = item.asInstanceOf[Row].toSeq.iterator

            while (convertersIterator.hasNext) {
              val converter = convertersIterator.next()
              record.put(fieldNamesIterator.next(), converter(rowIterator.next()))
            }
            record
          }
        }
    }
  }

}
