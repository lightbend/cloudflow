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

import scala.concurrent.duration._
import org.apache.spark.sql.{Dataset, Encoder, SparkSession}
import org.apache.spark.sql.streaming.{OutputMode, Trigger}
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import cloudflow.spark.avro._
import cloudflow.spark.testkit._
import cloudflow.spark.sql.SQLImplicits._

class SparkEgressSpec extends SparkScalaTestSupport {

  "SparkEgress" should {
    "materialize streaming data to sink" in {

      val testKit = SparkStreamletTestkit(session)

      def asCollection[T: Encoder](session: SparkSession, queryName: String): List[T] =
        session.sql(s"select * from $queryName").as[T].collect().toList

      object MySparkEgress extends SparkStreamlet {
        val in    = AvroInlet[Data]("in")
        val shape = StreamletShape(in)
        override def createLogic() = new SparkStreamletLogic {
          override def buildStreamingQueries =
            process(readStream(in))

          private def process(inDataset: Dataset[Data]): StreamletQueryExecution = {
            val q1 = inDataset
              .map { d ⇒
                d.name
              }
              .writeStream
              .format("memory")
              .option("truncate", false)
              .queryName("allNames")
              .outputMode(OutputMode.Append())
              .trigger(Trigger.Once)
              .start()

            val q2 = inDataset
              .map { d ⇒
                d.name.toUpperCase
              }
              .writeStream
              .format("memory")
              .option("truncate", false)
              .queryName("allNamesUpper")
              .outputMode(OutputMode.Append())
              .trigger(Trigger.Once)
              .start()
            StreamletQueryExecution(q1, q2)
          }
        }
      }

      // setup inlet tap on inlet port
      val in: SparkInletTap[Data] = testKit.inletAsTap[Data](MySparkEgress.in)

      // build data and send to inlet tap
      val data = (1 to 10).map(i ⇒ Data(i, s"name$i"))
      in.addData(data)

      testKit.run(MySparkEgress, Seq(in), Seq.empty, 30.seconds)

      val r1 = asCollection[String](session, "allNames")
      val r2 = asCollection[String](session, "allNamesUpper")

      // assert
      r1 must contain("name1")
      r2 must contain("NAME1")
    }
  }
}
