package com.example

//tag::imports[]
import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.testkit._

import org.scalatest._
import org.scalatest.concurrent._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastream.testkit._
import cloudflow.akkastream.testkit.scaladsl._
//end::imports[]

//tag::test[]
class TestProcessorSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  private implicit val system = ActorSystem("AkkaStreamletSpec")
  private implicit val mat = ActorMaterializer()

  //tag::afterAll[]
  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }
  //end::afterAll[]

  "An TestProcessor" should {

    val testkit = AkkaStreamletTestKit(system, mat)

    "Allow for creating a 'flow processor'" in {
      val data = Vector(Data(1, "a"), Data(2, "b"), Data(3, "c"))
      val expectedData = Vector(Data(2, "b"))
      val source = Source(data)
      val proc = new TestProcessor
      val in = testkit.inletFromSource(proc.in, source)
      val out = testkit.outletAsTap(proc.out)

      testkit.run(proc, in, out, () ⇒ {
        out.probe.receiveN(1) mustBe expectedData.map(d ⇒ proc.out.partitioner(d) -> d)
      })

      out.probe.expectMsg(Completed)
    }
  }
}
//end::test[]