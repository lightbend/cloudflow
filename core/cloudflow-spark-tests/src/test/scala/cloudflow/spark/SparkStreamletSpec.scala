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
import org.apache.spark.sql.streaming.{ OutputMode, StreamingQuery }
import cloudflow.streamlets._
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import cloudflow.spark.avro._
import cloudflow.spark.testkit._
import cloudflow.spark.sql.SQLImplicits._
import com.typesafe.config.ConfigFactory
import org.scalatest.OptionValues

import scala.concurrent.{ Await, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Success

class SparkStreamletSpec extends SparkScalaTestSupport with OptionValues {

  "SparkStreamlet runtime" should {

    "automatically stop the streamlet execution when a managed query stops" in {
      val instance = new LeakySparkProcessor()
      // setup outlet tap on outlet port
      val testKit                      = SparkStreamletTestkit(session)
      val out1: SparkOutletTap[Simple] = testKit.outletAsTap[Simple](instance.out1)
      val out2: SparkOutletTap[Simple] = testKit.outletAsTap[Simple](instance.out2)
      val ctx                          = new TestSparkStreamletContext("test-streamlet", session, Nil, Seq(out1, out2), ConfigFactory.empty())

      val stopActiveQuery = Future {
        def attemptStopQuery(): Unit = {
          Thread.sleep(1000)
          instance.queries.headOption
            .map { query =>
              if (query.isActive) {
                query.stop
              } else {
                attemptStopQuery()
              }
            }
            .getOrElse(attemptStopQuery())
        }
        attemptStopQuery()
      }
      val execution       = instance.setContext(ctx).run(ctx)
      val completedStatus = execution.completed
      // sanity check
      Await.result(stopActiveQuery, 30.seconds)
      Await.ready(completedStatus, 30.seconds)

      // execution should have stopped right after one of the queries stopped,
      completedStatus.value.value mustBe (Success(Dun))
    }

    "automatically stop the streamlet execution when a managed query fails" in {
      val instance = new LeakySparkProcessor()
      // setup outlet tap on outlet port
      val testKit                      = SparkStreamletTestkit(session)
      val out1: SparkOutletTap[Simple] = testKit.outletAsTap[Simple](instance.out1)
      val out2: SparkOutletTap[Simple] = testKit.outletAsTap[Simple](instance.out2)
      val ctx                          = new TestSparkStreamletContext("test-streamlet", session, Nil, Seq(out1, out2), ConfigFactory.empty())

      val execution = instance.setContext(ctx).run(ctx)

      val failActiveQuery = execution.ready.andThen {
        case _ =>
          instance.mustFail(true)
      }

      val completedStatus = execution.completed
      // sanity check
      Await.result(failActiveQuery, 30.seconds)
      // wait for the execution to complete
      Await.ready(completedStatus, 30.seconds)

      // execution should have stopped right after one of the queries stopped,
      completedStatus.value.value mustBe ('Failure)
    }

  }

}

trait QueryAccess {
  def queries: Seq[StreamingQuery]
  def mustFail(fail: Boolean)
}
class LeakySparkProcessor extends SparkStreamlet with QueryAccess {
  val out1                                   = AvroOutlet[Simple]("out1")
  val out2                                   = AvroOutlet[Simple]("out2")
  val shape                                  = StreamletShape.withOutlets(out1, out2)
  @volatile var queries: Seq[StreamingQuery] = Seq()
  @volatile var shouldFail                   = false
  override def mustFail(fail: Boolean)       = shouldFail = fail

  override def createLogic() = new SparkStreamletLogic {

    override def buildStreamingQueries = {
      import org.apache.spark.sql.functions._
      val inStream = session.readStream
        .format("rate")
        .load()
        .select(concat(lit("value-"), $"value").as("name"))
        .as[Simple]
      val outStream1 = writeStream(inStream, out1, OutputMode.Append)
      val mayFailStream = inStream.map { value =>
        if (shouldFail) throw new RuntimeException("InternalFailure")
        value
      }
      val outStream2 = writeStream(mayFailStream, out2, OutputMode.Append)
      queries = Seq(outStream1, outStream2)
      StreamletQueryExecution(queries)
    }
  }
}
