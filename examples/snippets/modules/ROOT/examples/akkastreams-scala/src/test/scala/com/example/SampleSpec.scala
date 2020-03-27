package com.example

import akka.actor._
import akka.stream._
import akka.testkit._

import org.scalatest._
import cloudflow.akkastream.testkit._
import cloudflow.akkastream.testkit.scaladsl._
import cloudflow.akkastreamsdoc.RecordSumFlow

class SampleSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  private implicit val system = ActorSystem("AkkaStreamletSpec")
  private implicit val mat    = ActorMaterializer()

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  "An TestProcessor" should {

    //tag::config-value[]
    val testkit =
      AkkaStreamletTestKit(system, mat).withConfigParameterValues(ConfigParameterValue(RecordSumFlow.recordsInWindowParameter, "20"))
    //end::config-value[]

    "Allow for creating a 'flow processor'" in {
      val a = 1
      a must equal(1)
    }
  }
}
