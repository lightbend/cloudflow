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

package generator

import java.sql.Timestamp

import scala.util.Random
import scala.concurrent.duration._

import org.apache.spark.sql.{ Dataset, SparkSession }
import org.apache.spark.sql.streaming.OutputMode

import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.LongType

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.spark.sql.SQLImplicits._
import datamodel._
import cloudflow.spark.{ SparkStreamlet, SparkStreamletLogic }
import org.apache.log4j.{ Level, Logger }

case class Rate(timestamp: Timestamp, value: Long)

class Generator extends SparkStreamlet {

  val rootLogger = Logger.getRootLogger()
  rootLogger.setLevel(Level.ERROR)

  val RecordsPerSecond = IntegerConfigParameter("records-per-second", "Records per second to process.", Some(50))

  override def configParameters = Vector(RecordsPerSecond)

  val out   = AvroOutlet[Data]("out", _.id.toString)
  val shape = StreamletShape(out)

  override def createLogic() = new SparkStreamletLogic {
    val recordsPerSecond = RecordsPerSecond.value
    override def buildStreamingQueries = {
      val outStream = DataGenerator.mkData(super.session, recordsPerSecond)
      writeStream(outStream, out, OutputMode.Append).toQueryExecution
    }
  }
}

object DataGenerator {
  def mkData(session: SparkSession, recordsPerSecond: Int): Dataset[Data] = {

    // Random Data Generator
    val idUdf     = udf(() => Random.nextLong())
    val msgUdf = udf(() => Random.alphanumeric.take(32).mkString + "-spark" )

    val rateStream = session.readStream
      .format("rate")
      .option("rowsPerSecond", recordsPerSecond)
      .load()
      .as[Rate]

    val randomDataset = rateStream.withColumn("rng", rand())
    val sampledData = randomDataset
      .withColumn("id", idUdf())
      .withColumn("msg", msgUdf())
      .select($"id", $"msg")
      .as[Data]

    sampledData
  }
}
