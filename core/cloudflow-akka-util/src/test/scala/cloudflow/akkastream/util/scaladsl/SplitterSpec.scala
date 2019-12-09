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

package cloudflow.akkastream.util.scaladsl

import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.testkit._
import org.scalatest._
import org.scalatest.concurrent._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream._
import cloudflow.akkastream.testkit.scaladsl._
import cloudflow.akkastream.testdata._

class SplitterSpec extends WordSpec with MustMatchers with ScalaFutures with BeforeAndAfterAll {
  private implicit val system = ActorSystem("SplitterSpec")
  private implicit val mat = ActorMaterializer()

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  object MyPartitioner extends AkkaStreamlet {
    val in = AvroInlet[Data]("in")
    val left = AvroOutlet[BadData]("out-0", _.name.toString)
    val right = AvroOutlet[Data]("out-1", _.id.toString)
    val shape = StreamletShape(in).withOutlets(left, right)

    override def createLogic = new SplitterLogicAkka(in, left, right) {
      def flow = {
        flowWithOffsetContext().map { data ⇒
          if (data.id % 2 == 0) Right(data) else Left(BadData(data.name))
        }
      }
    }
  }

  "A Splitter" should {
    "split incoming data according to a splitter flow" in {
      val testkit = AkkaStreamletTestKit(system, mat)
      val source = Source(Vector(Data(1, "a"), Data(2, "b")))

      val in = testkit.inletFromSource(MyPartitioner.in, source)
      val left = testkit.outletAsTap(MyPartitioner.left)
      val right = testkit.outletAsTap(MyPartitioner.right)

      testkit.run(MyPartitioner, in, List(left, right), () ⇒ {
        left.probe.expectMsg(("a", BadData("a")))
        right.probe.expectMsg(("2", Data(2, "b")))
      })

      left.probe.expectMsg(Completed)
      right.probe.expectMsg(Completed)
    }
  }
}
