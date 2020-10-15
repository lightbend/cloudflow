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

package sensors.proto

import cloudflow.spark.sql.SQLImplicits.StringToColumn
import scalapb.spark.Implicits._

import cloudflow.spark.{ SparkStreamlet, SparkStreamletLogic }
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto._
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.TimestampType

class MovingAverageSparklet extends SparkStreamlet {

  val in    = ProtoInlet[Data]("in")
  val out   = ProtoOutlet[Agg]("out", _.src)
  val shape = StreamletShape(in, out)

  override def createLogic() = new SparkStreamletLogic {
    override def buildStreamingQueries = {
      val dataset   = readStream(in)
      val outStream = process(dataset)
      writeStream(outStream, out, OutputMode.Append).toQueryExecution
    }

    private def process(inDataset: Dataset[Data]): Dataset[Agg] = {
      val query = inDataset
        .withColumn("ts", $"timestamp".cast(TimestampType))
        .withWatermark("ts", "1 minutes")
        .groupBy(window($"ts", "1 minute", "30 seconds"), $"src", $"gauge")
        .agg(avg($"value").as("avg"))
      query.select($"src", $"gauge", $"avg".as("value")).as[Agg]
    }
  }
}
