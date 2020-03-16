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

import scala.collection.immutable.Seq
import scala.concurrent.duration._

import carly.data._

import cloudflow.spark.testkit._
import cloudflow.spark.sql.SQLImplicits._

class CallRecordGeneratorIngressSpec extends SparkScalaTestSupport {

  val streamlet = new CallRecordGeneratorIngress()
  val testKit = SparkStreamletTestkit(session).withConfigParameterValues(ConfigParameterValue(streamlet.RecordsPerSecond, "1"))

  "CallRecordGeneratorIngress" should {
    "produce elements to its outlet" in {

      // setup outlet tap on outlet port
      val out = testKit.outletAsTap[CallRecord](streamlet.out)

      testKit.run(streamlet, Seq.empty, Seq(out), 4500.milliseconds)

      // get data from outlet tap
      val results = out.asCollection(session)

      // assert
      results.size must be > 0

    }
  }
}

