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

package cloudflow.spark

import scala.collection.immutable.Seq
import scala.concurrent.duration._

import org.apache.spark.sql.streaming.OutputMode

import cloudflow.streamlets._
import cloudflow.spark.testkit._

import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import cloudflow.spark.avro._
import cloudflow.spark.testkit._
import cloudflow.spark.sql.SQLImplicits._

class SparkStreamletSpec extends SparkScalaTestSupport {

  "SparkStreamletSpec" should {
    "validate that a config parameter value can be set in a test" ignore {
      // create sparkStreamlet
      object MySparkProcessor extends SparkStreamlet {
        val in    = AvroInlet[Data]("in")
        val out   = AvroOutlet[Simple]("out", _.name)
        val shape = StreamletShape(in, out)

        val NameFilter = StringConfigParameter("name-filter-value", "Filters out the data in the stream that matches this name.", Some("a"))

        override def configParameters = Vector(NameFilter)

        override def createLogic() = new SparkStreamletLogic {
          val nameFilter = context.streamletConfig.getString(NameFilter.key)

          override def buildStreamingQueries = {
            val outStream = readStream(in).select($"name").filter($"name" === nameFilter).as[Simple]
            val query     = writeStream(outStream, out, OutputMode.Append)
            query.toQueryExecution
          }
        }
      }

      val configTestKit =
        SparkStreamletTestkit(session).withConfigParameterValues(ConfigParameterValue(MySparkProcessor.NameFilter, "name5"))

      // setup inlet tap on inlet port
      val in: SparkInletTap[Data] = configTestKit.inletAsTap[Data](MySparkProcessor.in)

      // setup outlet tap on outlet port
      val out: SparkOutletTap[Simple] = configTestKit.outletAsTap[Simple](MySparkProcessor.out)

      // build data and send to inlet tap
      val data = (1 to 10).map(i ⇒ Data(i, s"name$i"))
      in.addData(data)

      configTestKit.run(MySparkProcessor, Seq(in), Seq(out), 2.seconds)

      // get data from outlet tap
      val results = out.asCollection(session)

      // assert
      results mustBe Array(Simple("name5"))
    }
  }
}
