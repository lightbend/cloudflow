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

package carly.aggregator

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.spark.{ SparkStreamlet, SparkStreamletLogic }
import org.apache.spark.sql.streaming.OutputMode
import cloudflow.spark.sql.SQLImplicits._
import org.apache.log4j.{ Level, Logger }

import carly.data._
class CallStatsAggregator extends SparkStreamlet {

  val rootLogger = Logger.getRootLogger()
  rootLogger.setLevel(Level.ERROR)

  //tag::docs-schemaAware-example[]
  val in    = AvroInlet[CallRecord]("in")
  val out   = AvroOutlet[AggregatedCallStats]("out", _.startTime.toString)
  val shape = StreamletShape(in, out)
  //end::docs-schemaAware-example[]

  val GroupByWindow = DurationConfigParameter("group-by-window", "Window duration for the moving average computation", Some("1 minute"))

  val Watermark = DurationConfigParameter("watermark", "Late events watermark duration: how long to wait for late events", Some("1 minute"))

  override def configParameters = Vector(GroupByWindow, Watermark)
  override def createLogic = new SparkStreamletLogic {
    val watermark     = context.streamletConfig.getDuration(Watermark.key)
    val groupByWindow = context.streamletConfig.getDuration(GroupByWindow.key)

    //tag::docs-aggregationQuery-example[]
    override def buildStreamingQueries = {
      val dataset   = readStream(in)
      val outStream = process(dataset)
      writeStream(outStream, out, OutputMode.Update).toQueryExecution
    }

    private def process(inDataset: Dataset[CallRecord]): Dataset[AggregatedCallStats] = {
      val query =
        inDataset
          .withColumn("ts", $"timestamp".cast(TimestampType))
          .withWatermark("ts", s"${watermark.toMillis()} milliseconds")
          .groupBy(window($"ts", s"${groupByWindow.toMillis()} milliseconds"))
          .agg(avg($"duration").as("avgCallDuration"), sum($"duration").as("totalCallDuration"))
          .withColumn("windowDuration", $"window.end".cast(LongType) - $"window.start".cast(LongType))

      query
        .select($"window.start".cast(LongType).as("startTime"), $"windowDuration", $"avgCallDuration", $"totalCallDuration")
        .as[AggregatedCallStats]
    }
    //end::docs-aggregationQuery-example[]
  }
}
