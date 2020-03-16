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

package cloudflow.spark.kafka

import java.io.File

import com.typesafe.config.Config
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.encoders.{ ExpressionEncoder, RowEncoder }
import org.apache.spark.sql.streaming.{ OutputMode, StreamingQuery }
import cloudflow.spark.SparkStreamletContext
import cloudflow.spark.avro.{ SparkAvroDecoder, SparkAvroEncoder }
import cloudflow.spark.sql.SQLImplicits._
import cloudflow.streamlets._

import scala.reflect.runtime.universe._

class SparkStreamletContextImpl(
    private[cloudflow] override val streamletDefinition: StreamletDefinition,
    session: SparkSession,
    override val config: Config
) extends SparkStreamletContext(streamletDefinition, session) {

  val storageDir           = config.getString("storage.mountPath")
  val maxOffsetsPerTrigger = config.getLong("cloudflow.spark.read.options.max-offsets-per-trigger")
  def readStream[In](inPort: CodecInlet[In])(implicit encoder: Encoder[In], typeTag: TypeTag[In]): Dataset[In] = {

    implicit val inRowEncoder: ExpressionEncoder[Row] = RowEncoder(encoder.schema)
    val schema                                        = inPort.schemaAsString
    val savepointPath                                 = findSavepointPathForPort(inPort)
    val srcTopic                                      = savepointPath.value
    val brokers                                       = config.getString("cloudflow.kafka.bootstrap-servers")

    val src: DataFrame = session.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", brokers)
      .option("maxOffsetsPerTrigger", maxOffsetsPerTrigger)
      .option("subscribe", srcTopic)
      // Allow restart of stateful streamlets that may have been offline for longer than the kafka retention period.
      // This setting may result in data loss in some cases but allows for continuity of the runtime
      .option("failOnDataLoss", false)
      .option("startingOffsets", "earliest")
      .load()

    val rawDataset = src.select($"value").as[Array[Byte]]

    val dataframe: Dataset[Row] = rawDataset.mapPartitions { iter ⇒
      val avroDecoder = new SparkAvroDecoder[In](schema)
      iter.map(avroDecoder.decode)
    }(inRowEncoder)

    dataframe.as[In]
  }

  def writeStream[Out](stream: Dataset[Out], outPort: CodecOutlet[Out], outputMode: OutputMode)(implicit encoder: Encoder[Out],
                                                                                                typeTag: TypeTag[Out]): StreamingQuery = {

    val avroEncoder   = new SparkAvroEncoder[Out](outPort.schemaAsString)
    val encodedStream = avroEncoder.encodeWithKey(stream, outPort.partitioner)

    val savepointPath = findSavepointPathForPort(outPort)
    val destTopic     = savepointPath.value
    val brokers       = config.getString("cloudflow.kafka.bootstrap-servers")

    // metadata checkpoint directory on mount
    val checkpointLocation = checkpointDir(outPort.name)
    val queryName          = s"$streamletRef.$outPort"

    encodedStream.writeStream
      .outputMode(outputMode)
      .format("kafka")
      .queryName(queryName)
      .option("kafka.bootstrap.servers", brokers)
      .option("topic", destTopic)
      .option("checkpointLocation", checkpointLocation)
      .start()
  }

  def checkpointDir(dirName: String): String = {
    val baseCheckpointDir = new File(storageDir, streamletRef)
    val dir               = new File(baseCheckpointDir, dirName)
    if (!dir.exists()) {
      val created = dir.mkdirs()
      require(created, s"Could not create checkpoint directory: $dir")
    }
    dir.getAbsolutePath
  }
}
