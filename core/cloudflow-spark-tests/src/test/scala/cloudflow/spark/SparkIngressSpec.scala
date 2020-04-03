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
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.execution.streaming.MemoryStream
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import cloudflow.spark.avro._
import cloudflow.spark.testkit._
import cloudflow.spark.sql.SQLImplicits._

class SparkIngressSpec extends SparkScalaTestSupport {

  "SparkIngress" should {
    "produce elements to its outlet" in {

      val testKit  = SparkStreamletTestkit(session)
      val instance = new MySparkIngress()

      // setup outlet tap on outlet port
      val out: SparkOutletTap[Data] = testKit.outletAsTap[Data](instance.out)

      val run = testKit.run(instance, Seq.empty, Seq(out))

      // get processed rows from the run
      run.totalRows must be(10)

      // get data from outlet tap
      val results = out.asCollection(session)

      // assert
      results must contain(Data(1, "name1"))
    }
  }
}
// create sparkStreamlet
class MySparkIngress extends SparkStreamlet {
  val out   = AvroOutlet[Data]("out", d ⇒ d.id.toString)
  val shape = StreamletShape(out)

  override def createLogic() = new SparkStreamletLogic {
    private def process: Dataset[Data] = {
      implicit val sqlCtx = session.sqlContext
      val data            = (1 to 10).map(i ⇒ Data(i, s"name$i"))
      val m               = MemoryStream[Data]
      m.addData(data)
      m.toDF.as[Data]
    }
    override def buildStreamingQueries = {
      val outStream: Dataset[Data] = process
      require(outStream.isStreaming, "The Dataset created by an Ingress must be a Streaming Dataset")
      val query = writeStream(outStream, out, OutputMode.Append)
      StreamletQueryExecution(query)
    }
  }
}
