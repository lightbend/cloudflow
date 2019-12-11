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

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.NotUsed
import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.testkit._

import org.scalatest._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastream.testdata._
import cloudflow.akkastream.testkit.scaladsl._

class AkkaStreamletSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  private implicit val system = ActorSystem("AkkaStreamletSpec")
  private implicit val mat = ActorMaterializer()
  val timeout = 10.seconds.dilated
  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "An AkkaStreamlet" should {
    val testkit = AkkaStreamletTestKit(system, mat)

    "Allow for querying of configuration parameters" in {
      object ConfigTestProcessor extends AkkaStreamlet {
        val in = AvroInlet[Data]("in")
        val out = AvroOutlet[Data]("out")
        final override val shape = StreamletShape.withInlets(in).withOutlets(out)

        val NameFilter = StringConfigParameter(
          "name-filter-value",
          "Filters out the data in the stream that matches this string.",
          Some("a"))

        override def configParameters = Vector(NameFilter)

        override final def createLogic = new RunnableGraphStreamletLogic() {
          val nameFilter = streamletConfig.getString(NameFilter.key)
          val flow = Flow[Data].filter(data ⇒ data.name == nameFilter)
          def runnableGraph = plainSource(in).via(flow).to(plainSink(out))
        }
      }

      val configTestKit =
        AkkaStreamletTestKit(system, mat)
          .withConfigParameterValues(ConfigParameterValue(ConfigTestProcessor.NameFilter, "b"))

      val data = Vector(Data(1, "a"), Data(2, "b"), Data(3, "c"))
      val source = Source(data)
      val in = testkit.inletFromSource(ConfigTestProcessor.in, source)
      val out = testkit.outletAsTap(ConfigTestProcessor.out)

      configTestKit.run(ConfigTestProcessor, in, out, () ⇒ {
        out.probe.receiveN(1) mustBe Vector(Data(2, "b")).map(d ⇒ ConfigTestProcessor.out.partitioner(d) -> d)
      })
    }

    "Verify that a call to `streamletContext` in a streamlet with no configuration parameters yields an empty config" in {

      object ConfigTestProcessor extends AkkaStreamlet {
        val in = AvroInlet[Data]("in")
        val out = AvroOutlet[Data]("out")
        final override val shape = StreamletShape.withInlets(in).withOutlets(out)

        override final def createLogic = new RunnableGraphStreamletLogic() {
          // The test
          streamletConfig mustBe empty

          def runnableGraph = sourceWithOffsetContext(in).to(sinkWithOffsetContext(out))
        }
      }

      val configTestKit = AkkaStreamletTestKit(system, mat)

      val data = Vector(Data(1, "a"), Data(2, "b"), Data(3, "c"))
      val source = Source(data)
      val in = testkit.inletFromSource(ConfigTestProcessor.in, source)
      val out = testkit.outletAsTap(ConfigTestProcessor.out)

      configTestKit.run(ConfigTestProcessor, in, out, () ⇒ {
        out.probe.receiveN(1) mustBe Vector(Data(1, "a")).map(d ⇒ ConfigTestProcessor.out.partitioner(d) -> d)
      })
    }

    "Allow for creating an 'ingress'" in {
      val data = Vector(Data(1, "a"), Data(2, "b"), Data(3, "c"))
      val source = Source(data)
      val ingress = new TestIngress(source)

      val out = testkit.outletAsTap(ingress.out)

      testkit.run(ingress, out, () ⇒ {
        // TODO leave out key and need for partitioner when using probes.
        out.probe.receiveN(3) mustBe data.map(d ⇒ ingress.out.partitioner(d) -> d)
      })

      out.probe.expectMsg(Completed)
    }

    "Allow for creating an 'egress'" in {
      val data = Vector(Data(1, "a"), Data(2, "b"), Data(3, "c"))
      val source = Source(data)
      val sink = Sink.seq[Data]
      val egress = new TestEgress(sink)

      val in = testkit.inletFromSource(egress.in, source)
      testkit.run(egress, in, () ⇒ {
        egress.result mustBe data
      })
    }
  }

  class TestProcessorWithParameters extends AkkaStreamlet {
    val in = AvroInlet[Data]("in")
    val out = AvroOutlet[Data]("out", _.id.toString)
    final override val shape = StreamletShape.withInlets(in).withOutlets(out)

    val flow = Flow[Data]
    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph = plainSource(in).via(flow).to(plainSink(out))
    }
  }

  class TestProcessor extends AkkaStreamlet {
    val in = AvroInlet[Data]("in")
    val out = AvroOutlet[Data]("out", _.id.toString)
    final override val shape = StreamletShape.withInlets(in).withOutlets(out)

    val flow = Flow[Data]
    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph = plainSource(in).via(flow).to(plainSink(out))
    }
  }

  class TestIngress(source: Source[Data, NotUsed]) extends AkkaStreamlet {
    val out = AvroOutlet[Data]("out", _.id.toString)
    final override val shape = StreamletShape.withOutlets(out)

    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph = source.to(plainSink(out))
    }
  }

  class TestEgress[Mat](sink: Sink[Data, Future[Seq[Data]]]) extends AkkaStreamlet {
    val in = AvroInlet[Data]("in")
    var result: Seq[Data] = _
    final override val shape = StreamletShape.withInlets(in)

    override final def createLogic = new AkkaStreamletLogic() {
      def run() = {
        result = scala.concurrent.Await.result(plainSource(in).toMat(sink)(Keep.right).run, timeout)
      }
    }
  }
}
