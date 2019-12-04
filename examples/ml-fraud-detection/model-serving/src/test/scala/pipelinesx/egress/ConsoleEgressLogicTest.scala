package pipelinesx.egress

import pipelinesx.test.TestData
import org.scalatest.{BeforeAndAfterAll, FunSpec}
import akka.testkit._
import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import pipelines.streamlets.StreamletShape
import pipelines.streamlets.avro.AvroInlet
import com.typesafe.config.ConfigFactory
import java.io.{ByteArrayOutputStream, PrintStream}

import pipelines.akkastream.AkkaStreamlet
import pipelines.akkastream.testkit.scaladsl.AkkaStreamletTestKit

class ConsoleEgressLogicTest extends FunSpec with BeforeAndAfterAll {

  private implicit val system = ActorSystem("ConsoleEgressLogic")
  private implicit val mat = ActorMaterializer()

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  final class TestEgress extends AkkaStreamlet {
    val inlet = AvroInlet[TestData]("in")
    final override val shape = StreamletShape.withInlets(inlet)

    val bytesOS = new ByteArrayOutputStream
    val bytesPS = new PrintStream(bytesOS, true)
    override def createLogic = ConsoleEgressLogic[TestData](
      in = inlet,
      prefix = "TestPrefix",
      out = bytesPS)
  }

  def toString(expected: TestData) = s"""TestPrefix{"id": ${expected.id}, "name": ${expected.name}}"""

  describe("LogEgress") {
    // Currently ignored because this test simply doesn't work! No output is
    // captured in the byte array!
    // Investigate whether or not FlowEgressLogic should be implemented with
    // RunnableGraphStreamletLogic. Could that be the issue??
    ignore("Writes output to the user-specified output stream, stdout by default") {
      val data = Vector(TestData(1, "one"), TestData(2, "two"), TestData(3, "three"))
      val testEgress = new TestEgress()
      val testkit = AkkaStreamletTestKit(system, mat, ConfigFactory.load())
      val source = Source(data)
      val in = testkit.inletFromSource(testEgress.inlet, source)
      testkit.run(testEgress, in, Nil, () ⇒ {
        val lines = testEgress.bytesOS.toString.split("\n").toVector
        assert(lines.size == data.size, s"lines: $lines")
        lines.zip(data).foreach {
          case (actual, expected) ⇒
            assert(actual == toString(expected))
        }
      })
    }
  }
}
