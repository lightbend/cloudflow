/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.concurrent.duration._

import scala.util.Random

import carly.data._

import cloudflow.spark.testkit._
import cloudflow.spark.sql.SQLImplicits._

class CallStatsAggregatorSpec extends SparkScalaTestSupport {

  val streamlet = new CallStatsAggregator()
  val testKit = SparkStreamletTestkit(session).withConfigParameterValues(
    ConfigParameterValue(streamlet.GroupByWindow, "1 minute"),
    ConfigParameterValue(streamlet.Watermark, "1 minute"))

  "CallStatsAggregator" should {
    "produce elements to its outlet" in {

      // setup inlet tap on inlet port
      val in = testKit.inletAsTap[CallRecord](streamlet.in)

      // setup outlet tap on outlet port
      val out = testKit.outletAsTap[AggregatedCallStats](streamlet.out)

      val maxUsers = 10
      val crs = (1 to 30).toList.map { i ⇒
        CallRecord(
          s"user-${Random.nextInt(maxUsers)}",
          s"user-${Random.nextInt(maxUsers)}",
          (if (i % 2 == 0) "incoming" else "outgoing"),
          Random.nextInt(50),
          Instant.now.minus(Random.nextInt(40), ChronoUnit.MINUTES).toEpochMilli / 1000
        )
      }

      in.addData(crs)

      testKit.run(streamlet, Seq(in), Seq(out), 30.seconds)

      // get data from outlet tap
      val results = out.asCollection(session)

      // assert
      results.size must be > 0
    }
  }
}

