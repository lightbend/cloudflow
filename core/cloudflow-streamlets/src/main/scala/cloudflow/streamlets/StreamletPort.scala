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

package cloudflow.streamlets

/**
 * A named port handle handle to read or write data according to a schema.
 */
trait StreamletPort {
  def name: String
  def schemaDefinition: SchemaDefinition
}

/**
 * Describes the schema. `name` should be the unique name of the schema.
 * `schema` is a string representation of the schema itself. (In the case of the avro format this is a json document)
 * The `fingerprint` is a consistent hash of the schema.
 * The `format` specifies the format of the schema. Unique names should be used for different formats.
 * (In the case of Avro, format is "avro")
 */
final case class SchemaDefinition(
    name: String,
    schema: String,
    fingerprint: String,
    format: String
)

/**
 * A handle to read data according to a schema.
 */
trait Inlet extends StreamletPort

/**
 * A handle to write data according to a schema.
 */
trait Outlet extends StreamletPort

/**
 * A handle to read and deserialize data into elements of type `T`.
 */
trait CodecInlet[T] extends Inlet {

  /**
   * The codec is used to deserialize the data that is read from the inlet.
   */
  def codec: Codec[T]

  /**
   * Describes the schema used to deserialize the data.
   */
  def schemaAsString: String

  /**
   * Returns true when this inlet has a unique group Id, so that the inlet will receive data from all partitions.
   * This is useful for when you scale a streamlet, and you want all the streamlet instances to receive all the data.
   * If no unique group Id is set (which is the default), streamlet instances will each receive part of the data (on this inlet).
   */
  def hasUniqueGroupId: Boolean

  /**
   * Sets a unique group Id so that the inlet will receive data from all partitions.
   * This is useful for when you scale a streamlet, and you want all the streamlet instances to receive all the data.
   * If no unique group Id is set (which is the default), streamlet instances will each receive part of the data (on this inlet).
   */
  def withUniqueGroupId: CodecInlet[T]
}

/**
 * A handle to serialize elements of type `T` into a partitioned stream.
 */
trait CodecOutlet[T] extends Outlet {

  /**
   * Returns a CodecOutlet with the partitioner set.
   */
  def withPartitioner(partitioner: T ⇒ String): CodecOutlet[T]

  /**
   * Partitions the data that is written to the outlet.
   */
  def partitioner: T ⇒ String

  /**
   * Serializes the data that is written to the outlet.
   */
  def codec: Codec[T]

  /**
   * Describes the schema used to serialize the data.
   */
  def schemaAsString: String
}

/**
 * A round-robin partitioning function.
 * Elements written to a [[CodecOutlet]] that uses this partitioner will be distributed in round-robin fashion across the topic partitions.
 */
object RoundRobinPartitioner extends (Any ⇒ String) with Serializable {

  /**
   * The key is null for any record. The Kafka Producer will use the default (round-robin) partitioner
   * when a ProducerRecord contains a null key and when it has no partition id set.
   */
  def apply(any: Any): String = null

  /**
   * Java API
   */
  def getInstance[T <: Any]: T ⇒ String = this
}
