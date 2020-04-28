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

import java.time.Instant

import carly.data._
import cloudflow.spark.testkit._
import cloudflow.spark.sql.SQLImplicits._
import org.scalatest.OptionValues

class CallStatsAggregatorSpec extends SparkScalaTestSupport with OptionValues {

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

      val ts = Instant.now.toEpochMilli / 1000
      val crs = (1 to 10).toList.map { i ⇒
        CallRecord(
          s"user-1",
          s"user-2",
          (if (i % 2 == 0) "incoming" else "outgoing"),
          i*10,
          ts
        )
      }

      in.addData(crs)

      val run = testKit.run(streamlet, Seq(in), Seq(out))

      // get data from outlet tap
      val results = out.asCollection(session)

      // assert
      val aggregate = results.headOption.value
      aggregate.totalCallDuration must be (550)
      aggregate.avgCallDuration must (be > 54.9 and be < 55.1)
      run.totalRows must be > 0L
    }
  }
}

