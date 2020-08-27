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

package cloudflow.spark.avro

import org.apache.log4j.Logger

import java.io.ByteArrayOutputStream

import scala.reflect.runtime.universe._

import org.apache.avro.generic.{ GenericDatumReader, GenericDatumWriter, GenericRecord }
import org.apache.avro.io.{ DecoderFactory, EncoderFactory }
import org.apache.spark.sql.{ Dataset, Encoder, Row }
import org.apache.spark.sql.catalyst.encoders.{ encoderFor, ExpressionEncoder, RowEncoder }
import org.apache.spark.sql.catalyst.expressions.GenericRow
import org.apache.spark.sql.types.StructType
import org.apache.avro.Schema

import cloudflow.spark.sql.SQLImplicits._

case class EncodedKV(key: String, value: Array[Byte])

case class SparkAvroDecoder[T: Encoder: TypeTag](avroSchema: String) {

  val encoder: Encoder[T]                           = implicitly[Encoder[T]]
  val sqlSchema: StructType                         = encoder.schema
  val encoderForDataColumns: ExpressionEncoder[Row] = RowEncoder(sqlSchema)
  @transient lazy val _avroSchema                   = new Schema.Parser().parse(avroSchema)
  @transient lazy val rowConverter                  = SchemaConverters.createConverterToSQL(_avroSchema, sqlSchema)
  @transient lazy val datumReader                   = new GenericDatumReader[GenericRecord](_avroSchema)
  @transient lazy val decoder                       = DecoderFactory.get
  def decode(bytes: Array[Byte]): Row = {
    val binaryDecoder = decoder.binaryDecoder(bytes, null)
    val record        = datumReader.read(null, binaryDecoder)
    rowConverter(record).asInstanceOf[GenericRow]
  }

}

/**
 * Instances of this class are used to translate a Dataset[T] into a Byte Array
 * using an AVRO schema in its String JSON representation for the type T being encoded.
 * The type T must have a matching implicit Spark Encoder in scope.
 * Note that most supported Encoders can be imported from [[cloudflow.spark.sql.SQLImplicits]]
 */
case class SparkAvroEncoder[T: Encoder: TypeTag](avroSchema: String) {

  @transient lazy val log = Logger.getLogger(getClass.getName)

  val BufferSize = 5 * 1024 // 5 Kb

  val encoder                     = implicitly[Encoder[T]]
  val sqlSchema                   = encoder.schema
  @transient lazy val _avroSchema = new Schema.Parser().parse(avroSchema)

  val recordName                = "topLevelRecord" // ???
  val recordNamespace           = "recordNamespace" // ???
  @transient lazy val converter = AvroConverter.createConverterToAvro(sqlSchema, recordName, recordNamespace)

  // Risk: This process is memory intensive. Might require thread-level buffers to optimize memory usage
  def rowToBytes(row: Row): Array[Byte] = {
    val genRecord = converter(row).asInstanceOf[GenericRecord]
    if (log.isDebugEnabled) log.debug(s"genRecord = $genRecord")
    val datumWriter   = new GenericDatumWriter[GenericRecord](_avroSchema)
    val avroEncoder   = EncoderFactory.get
    val byteArrOS     = new ByteArrayOutputStream(BufferSize)
    val binaryEncoder = avroEncoder.binaryEncoder(byteArrOS, null)
    datumWriter.write(genRecord, binaryEncoder)
    binaryEncoder.flush()
    byteArrOS.toByteArray
  }

  def encode(dataset: Dataset[T]): Dataset[Array[Byte]] =
    dataset.toDF().mapPartitions(rows ⇒ rows.map(rowToBytes)).as[Array[Byte]]

  // Note to self: I'm not sure how heavy this chain of transformations is
  def encodeWithKey(dataset: Dataset[T], keyFun: T ⇒ String): Dataset[EncodedKV] = {
    val encoder             = encoderFor[T]
    implicit val rowEncoder = RowEncoder(encoder.schema).resolveAndBind()
    dataset.map { value ⇒
      val key         = keyFun(value)
      val internalRow = encoder.toRow(value)
      val row         = rowEncoder.fromRow(internalRow)
      val bytes       = rowToBytes(row)
      EncodedKV(key, bytes)
    }
  }

}
