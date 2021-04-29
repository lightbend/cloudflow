/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package swissknife.spark

import java.sql.Timestamp

import cloudflow.streamlets.{ IntegerConfigParameter, StreamletShape }
import cloudflow.streamlets.avro._
import cloudflow.spark.{ SparkStreamlet, SparkStreamletLogic }
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.functions._

import cloudflow.spark.sql.SQLImplicits._

import swissknife.data.Data

case class Rate(timestamp: Timestamp, value: Long)

class SparkDataGenerator extends SparkStreamlet {
  val out   = AvroOutlet[Data]("out", d ⇒ d.src)
  val shape = StreamletShape(out)

  val RecordsPerSecond = IntegerConfigParameter("records-per-second", "Records per second to produce.", Some(1))

  override def configParameters = Vector(RecordsPerSecond)

  override def createLogic() = new SparkStreamletLogic {

    override def buildStreamingQueries =
      writeStream(process, out, OutputMode.Append).toQueryExecution

    private def process: Dataset[Data] = {
      val recordsPerSecond = RecordsPerSecond.value
      session.readStream
        .format("rate")
        .option("rowsPerSecond", recordsPerSecond)
        .load()
        .select(lit("origin").as("src"), $"timestamp", lit("").as("payload"), $"value".as("count"))
        .as[Data]
    }
  }
}
