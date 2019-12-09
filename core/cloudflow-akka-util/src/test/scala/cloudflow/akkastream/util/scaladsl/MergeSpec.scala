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

import scala.collection.JavaConverters._

import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.testkit._
import org.scalatest._
import org.scalatest.concurrent._

import cloudflow.streamlets.avro._
import cloudflow.streamlets._
import cloudflow.akkastream._
import cloudflow.akkastream.testdata._
import cloudflow.akkastream.testkit.scaladsl._

class MergeSpec extends WordSpec with MustMatchers with ScalaFutures with BeforeAndAfterAll {

  private implicit val system = ActorSystem("MergeSpec")
  private implicit val mat = ActorMaterializer()

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "An MergeStreamlet" should {

    val testkit = AkkaStreamletTestKit(system, mat)
    def createScalaMergeStreamlet(inletCount: Int): TestMerge = {
      new ScalaTestMerge(inletCount)
    }

    def createJavaMergeStreamlet(inletCount: Int): TestMerge =
      new JavaTestMerge(inletCount)

    def createMergeStreamletInstances(inletCount: Int) = {
      Map("Scala MergeN" -> createScalaMergeStreamlet(inletCount), "Java MergeN" -> createJavaMergeStreamlet(inletCount))
    }

    createMergeStreamletInstances(2).foreach {
      case (name, mergeStreamletInstance) ⇒
        s"merge two streams into one using $name" in {
          val source0 = Source(Vector(Data(1, "a"), Data(3, "c"), Data(5, "e")))
          val source1 = Source(Vector(Data(2, "b"), Data(4, "d")))

          val in0 = testkit.inletFromSource(mergeStreamletInstance.inletPorts(0), source0)
          val in1 = testkit.inletFromSource(mergeStreamletInstance.inletPorts(1), source1)
          val out = testkit.outletAsTap(mergeStreamletInstance.outlet)

          testkit.run(mergeStreamletInstance, List(in0, in1), out, () ⇒ {

            out.probe.expectMsg(("1", Data(1, "a")))
            out.probe.expectMsg(("2", Data(2, "b")))
            out.probe.expectMsg(("3", Data(3, "c")))
            out.probe.expectMsg(("4", Data(4, "d")))
            out.probe.expectMsg(("5", Data(5, "e")))
          })

          out.probe.expectMsg(Completed)
        }
    }

    (2 to 10).foreach { inletCount ⇒
      createMergeStreamletInstances(inletCount).foreach {
        case (name, mergeStreamletInstance) ⇒
          s"merge many streams into one using $name with $inletCount inlets" in {
            val sources = (0 until inletCount).map(idx ⇒ Source(Vector(Data(idx, idx.toString))))
            val inletTaps = sources.zip(mergeStreamletInstance.inletPorts).map { case (src, inlet) ⇒ testkit.inletFromSource(inlet, src) }
            val out = testkit.outletAsTap(mergeStreamletInstance.outlet)
            testkit.run(mergeStreamletInstance, inletTaps.toList, out, () ⇒ {
              (0 until inletCount).map(idx ⇒ out.probe.expectMsg((idx.toString, Data(idx, idx.toString))))
            })

            out.probe.expectMsg(Completed)
          }
      }
    }

    "not accept inlet counts less than 2" in {
      for (i ← Seq(-1, 0, 1)) {
        assertThrows[IllegalArgumentException] {
          createScalaMergeStreamlet(i)
        }
        assertThrows[IllegalArgumentException] {
          createJavaMergeStreamlet(i)
        }
      }
    }
  }
}
// this is just for the test to access the inletPorts across Scala and Java
abstract class TestMerge extends AkkaStreamlet {
  def inletPorts: IndexedSeq[CodecInlet[Data]]
  def outlet: CodecOutlet[Data]
}

class ScalaTestMerge(inletCount: Int) extends TestMerge {
  require(inletCount >= 2)
  val outlet = AvroOutlet[Data]("out", _.id.toString)
  val inletPorts = (0 until inletCount).map(i ⇒ AvroInlet[Data](s"in-$i")).toIndexedSeq

  final override val shape = StreamletShape
    .withInlets(inletPorts.head, inletPorts.tail: _*)
    .withOutlets(outlet)

  /**
   * The streamlet logic for a `Merge` is fixed and merges inlet elements in the order they become
   * available to the streamlet.
   */
  override final def createLogic = new MergeLogicAkka(inletPorts, outlet)
}

class JavaTestMerge(inletCount: Int) extends TestMerge {
  require(inletCount >= 2)
  val outlet = AvroOutlet[Data]("out", _.id.toString)
  val inletPorts: IndexedSeq[CodecInlet[Data]] = (0 until inletCount).map(i ⇒ AvroInlet[Data](s"in-$i")).toIndexedSeq

  final override val shape = StreamletShape
    .withInlets(inletPorts.head, inletPorts.tail: _*)
    .withOutlets(outlet)

  /**
   * The streamlet logic for a `Merge` is fixed and merges inlet elements in the order they become
   * available to the streamlet.
   */
  override final def createLogic = new cloudflow.akkastream.util.javadsl.MergeLogicAkka(inletPorts.asJava, outlet, getStreamletContext())
}
