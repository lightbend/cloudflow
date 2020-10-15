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

import java.sql.Timestamp

import cloudflow.spark.{ SparkStreamlet, SparkStreamletLogic }
import cloudflow.streamlets.proto._
import cloudflow.streamlets.{ IntegerConfigParameter, StreamletShape }
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.streaming.OutputMode

import scalapb.spark.Implicits._
import org.apache.spark.sql.Encoders

import scala.util.Random

case class Rate(timestamp: Timestamp, value: Long)

class SparkRandomGenDataIngress extends SparkStreamlet {
  implicit val rateEncoder = Encoders.product[Rate]

  val out   = ProtoOutlet[Data]("out", d ⇒ d.src)
  val shape = StreamletShape(out)

  val RecordsPerSecond = IntegerConfigParameter("records-per-second", "Records per second to produce.", Some(50))

  override def configParameters = Vector(RecordsPerSecond)

  override def createLogic() = new SparkStreamletLogic {

    override def buildStreamingQueries =
      writeStream(process, out, OutputMode.Append).toQueryExecution

    private def process: Dataset[Data] = {

      val recordsPerSecond = RecordsPerSecond.value

      val gaugeGen: () ⇒ String = () ⇒ if (Random.nextDouble() < 0.5) "oil" else "gas"

      val rateStream = session.readStream
        .format("rate")
        .option("rowsPerSecond", recordsPerSecond)
        .load()
        .as[Rate]

      rateStream.map {
        case Rate(timestamp, value) ⇒ Data(s"src-${value % 100}", timestamp.getTime, gaugeGen(), Random.nextDouble() * value)
      }
    }
  }
}
