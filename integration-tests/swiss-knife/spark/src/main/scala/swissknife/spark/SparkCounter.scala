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

package swissknife

import cloudflow.streamlets.StreamletShape

import cloudflow.streamlets.avro._
import cloudflow.spark.{ SparkStreamlet, SparkStreamletLogic }

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.TimestampType
import cloudflow.spark.sql.SQLImplicits._
import org.apache.spark.sql.streaming.OutputMode

class SparkCounter extends SparkStreamlet {

  val in    = AvroInlet[Data]("in")
  val out   = AvroOutlet[Data]("out", _.src)
  val shape = StreamletShape(in, out)

  override def createLogic() = new SparkStreamletLogic {
    override def buildStreamingQueries = {
      val dataset   = readStream(in)
      val outStream = process(dataset)
      writeStream(outStream, out, OutputMode.Append).toQueryExecution
    }

    private def process(inDataset: Dataset[Data]): Dataset[Data] = {
      val query = inDataset
        .withColumn("ts", $"timestamp".cast(TimestampType))
        .withColumn("updated_src", concat($"src", lit("-spark")))
        .withWatermark("ts", "0 seconds")
        .groupBy(window($"ts", "5 seconds"), $"updated_src")
        .agg(count($"src").as("count"))
      query.select($"updated_src".as("src"), $"window.start".as("timestamp"), $"count").as[Data]
    }
  }

}
