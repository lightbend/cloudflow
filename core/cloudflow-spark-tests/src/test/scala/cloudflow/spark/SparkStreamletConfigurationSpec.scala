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

import org.apache.spark.sql.streaming.OutputMode
import org.scalatest.OptionValues

import cloudflow.spark.avro._
import cloudflow.spark.sql.SQLImplicits._
import cloudflow.spark.testkit._
import cloudflow.streamlets.{ StreamletShape, _ }
import cloudflow.streamlets.avro._

class SparkStreamletConfigurationSpec extends SparkScalaTestSupport with OptionValues {

  class MySparkProcessor extends SparkStreamlet {
    val in    = AvroInlet[Data]("in")
    val out   = AvroOutlet[Simple]("out", _.name)
    val shape = StreamletShape(in, out)

    val NameFilter =
      StringConfigParameter("name-filter-value", "Filters out the data in the stream that matches this name.", Some("initial"))

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

  "SparkStreamlet configuration support" should {

    val instance = new MySparkProcessor()

    val sampleData = {
      val (i, u, o) = ("initial", "updated", "other")
      Seq(i, u, i, u, o, u, o).zipWithIndex
        .map { case (elem, idx) => Data(idx, elem) }
    }

    "use the default value of a config parameter if the config parameter is not set" ignore {
      val configTestKit = SparkStreamletTestkit(session)
      // setup inlet tap on inlet port
      val in: SparkInletTap[Data] = configTestKit.inletAsTap[Data](instance.in)
      // setup outlet tap on outlet port
      val out: SparkOutletTap[Simple] = configTestKit.outletAsTap[Simple](instance.out)
      // send to inlet tap
      in.addData(sampleData)
      // run the stream
      val run = configTestKit.run(instance, in, out)
      // get data from outlet tap
      val results = out.asCollection(session)
      // assert
      run.totalRows must be(sampleData.size)
      results mustBe Array.fill(2)(Simple("initial"))
    }

    "validate that a config parameter value can be set in a test" in {
      val configTestKit =
        SparkStreamletTestkit(session).withConfigParameterValues(ConfigParameterValue(instance.NameFilter, "updated"))

      // setup inlet tap on inlet port
      val in: SparkInletTap[Data] = configTestKit.inletAsTap[Data](instance.in)

      // setup outlet tap on outlet port
      val out: SparkOutletTap[Simple] = configTestKit.outletAsTap[Simple](instance.out)

      // build data and send to inlet tap
      in.addData(sampleData)

      val run = configTestKit.run(instance, in, out)

      // get data from outlet tap
      val results = out.asCollection(session)

      // assert
      run.totalRows must be(sampleData.size)
      results mustBe Array.fill(3)(Simple("updated"))
    }
  }
}
