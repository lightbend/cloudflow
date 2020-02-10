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

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.spark.{ SparkStreamlet, SparkStreamletLogic }
import cloudflow.spark.sql.SQLImplicits._
import org.apache.spark.sql.streaming.OutputMode

import org.apache.log4j.{ Level, Logger }

import carly.data._

class CallAggregatorConsoleEgress extends SparkStreamlet {

  val rootLogger = Logger.getRootLogger()
  rootLogger.setLevel(Level.ERROR)

  val in    = AvroInlet[AggregatedCallStats]("in")
  val shape = StreamletShape(in)

  override def createLogic = new SparkStreamletLogic {
    override def buildStreamingQueries =
      readStream(in).writeStream
        .format("console")
        .outputMode(OutputMode.Append())
        .start()
        .toQueryExecution
  }
}
