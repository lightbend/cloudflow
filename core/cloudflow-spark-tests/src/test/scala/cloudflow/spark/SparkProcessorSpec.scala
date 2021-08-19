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

package cloudflow.spark

import scala.collection.immutable.Seq
import org.apache.spark.sql.streaming.OutputMode
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import cloudflow.spark.avro._
import cloudflow.spark.testkit._
import cloudflow.spark.sql.SQLImplicits._

class SparkProcessorSpec extends SparkScalaTestSupport {

  "SparkProcessor" should {
    "process streaming data" in {

      val testKit = SparkStreamletTestkit(session)

      // create an instance of the streamlet under test
      val instance = new TestSparkProcessor()

      // setup inlet tap on inlet port
      val in: SparkInletTap[Data] = testKit.inletAsTap[Data](instance.in)

      // setup outlet tap on outlet port
      val out: SparkOutletTap[Simple] = testKit.outletAsTap[Simple](instance.out)

      // build data and send to inlet tap
      val data = (1 to 10).map(i => Data(i, s"name$i"))
      in.addData(data)

      val run = testKit.run(instance, Seq(in), Seq(out))
      run.totalRows must be(10)

      // get data from outlet tap
      val results = out.asCollection(session)

      // assert
      results must contain(Simple("name1"))
    }
  }
}
// Test sparkStreamlet
class TestSparkProcessor extends SparkStreamlet {
  val in = AvroInlet[Data]("in")
  val out = AvroOutlet[Simple]("out", _.name)
  val shape = StreamletShape(in, out)

  override def createLogic() = new SparkStreamletLogic {
    override def buildStreamingQueries = {
      val dataset = readStream(in)
      val outStream = dataset.select($"name").as[Simple]
      val query = writeStream(outStream, out, OutputMode.Append)
      StreamletQueryExecution(query)
    }
  }
}
