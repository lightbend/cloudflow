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

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.streaming.OutputMode
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import cloudflow.spark.avro._
import cloudflow.spark.testkit._
import cloudflow.spark.sql.SQLImplicits._

class SparkJoin3Spec extends SparkScalaTestSupport {

  "SparkJoin3" should {
    "process streaming data" in {

      val testKit = SparkStreamletTestkit(session)

      val instance = new MySparkJoin3()

      // setup inlet tap on inlet port
      val in0: SparkInletTap[Data] = testKit.inletAsTap[Data](instance.in0)
      val in1: SparkInletTap[Data] = testKit.inletAsTap[Data](instance.in1)
      val in2: SparkInletTap[Data] = testKit.inletAsTap[Data](instance.in2)

      // setup outlet tap on outlet port
      val out: SparkOutletTap[Simple] = testKit.outletAsTap[Simple](instance.out)

      // build data and send to inlet tap
      val List(d1, d2, d3) = (1 to 30).map(i ⇒ Data(i, s"name$i")).sliding(10, 10).toList
      in0.addData(d1)
      in1.addData(d2)
      in2.addData(d3)

      val run = testKit.run(instance, Seq(in0, in1, in2), Seq(out))
      run.totalRows must be(30)

      // get data from outlet tap
      val results = out.asCollection(session)

      // assert
      results must contain(Simple("name1"))
      results must contain(Simple("name11"))
      results must contain(Simple("name21"))
      (results must have).length(30)
    }
  }
}
// create sparkStreamlet
class MySparkJoin3 extends SparkStreamlet {
  // comment: all inlets could be in different formats, one proto, one avro, one csv..
  val in0 = AvroInlet[Data]("in0")
  val in1 = AvroInlet[Data]("in1")
  val in2 = AvroInlet[Data]("in2")
  val out = AvroOutlet[Simple]("out", _.name)

  val shape = StreamletShape(out).withInlets(in0, in1, in2)

  override def createLogic() = new SparkStreamletLogic {
    override def buildStreamingQueries = {
      val dataset0                   = readStream(in0)
      val dataset1                   = readStream(in1)
      val dataset2                   = readStream(in2)
      val outStream: Dataset[Simple] = process(dataset0, dataset1, dataset2)
      val query                      = writeStream(outStream, out, OutputMode.Append)
      StreamletQueryExecution(query)
    }

    private def process(in0: Dataset[Data], in1: Dataset[Data], in2: Dataset[Data]): Dataset[Simple] =
      in0.union(in1.union(in2)).select($"name").as[Simple]
  }
}
