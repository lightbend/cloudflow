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

package cloudflow.akkastream.testkit

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ RunnableGraph, Source }
import akka.testkit.TestKit
import cloudflow.akkastream.{ AkkaStreamlet, AkkaStreamletLogic }
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.akkastream.testdata.TestData
import cloudflow.akkastream.testkit.scaladsl._
import cloudflow.streamlets.avro.{ AvroInlet, AvroOutlet }
import org.scalatest._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.must.Matchers
import scala.concurrent.Future

class TestSinkRefSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  private implicit val system = ActorSystem("CloudflowAkkaTestkitErrorReproducerSpec")

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  object TestFixture {
    val msgs = List.tabulate(10)(i => TestData(i, i.toString))

    class TestStreamlet extends AkkaStreamlet {
      val in = AvroInlet[TestData]("in")
      val out = AvroOutlet[TestData]("out")

      override val shape: StreamletShape = StreamletShape(in).withOutlets(out)

      override protected def createLogic(): AkkaStreamletLogic = new RunnableGraphStreamletLogic() {
        override def runnableGraph(): RunnableGraph[_] = {
          val snk = sinkRef(out)
          sourceWithCommittableContext(in)
            .mapAsync(parallelism = 1) { element =>
              snk.write(element)
            }
            .to(committableSink)
        }
      }
    }
  }

  import TestFixture._

  "Cloudflow Akka TestKit" should {
    "emit a dedicated completed messages after each message emitted via sinkRef.write, but should not" in {
      val testkit = AkkaStreamletTestKit(system)
      val s = new TestStreamlet()
      val in = testkit.inletFromSource(s.in, Source(msgs))
      val out = testkit.outletAsTap(s.out)

      testkit.run(s, List(in), List(out), () => {
        val results = out.probe.receiveN(msgs.size)

        val resultWithoutIndex = results.asInstanceOf[Seq[(_, TestData)]].map(_._2)
        resultWithoutIndex must contain allElementsOf msgs
      })
    }

    (0 until 300).foreach { i =>
      s"maintain the order in which messages are emitted via sinkRef.write (run #$i), but should not" in {
        val testkit = AkkaStreamletTestKit(system)
        val s = new TestStreamlet()
        val in = testkit.inletFromSource(s.in, Source(msgs))
        val out = testkit.outletAsTap(s.out)

        testkit.run(s, List(in), List(out), () => {
          val got = out.probe
            .receiveN(msgs.size)
            .map {
              case (_, m: TestData) => m
            }
          got mustEqual msgs
        })
      }
    }
  }

}
