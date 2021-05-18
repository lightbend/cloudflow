/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package carly.ingestor

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor._
import akka.stream.scaladsl._
import akka.testkit._
import org.scalatest._
import org.scalatest.wordspec._
import org.scalatest.matchers.should._
import org.scalatest.concurrent._

import cloudflow.akkastream.testkit.scaladsl._
import carly.data._

class CallRecordSplitSpec extends AnyWordSpec with Matchers with ScalaFutures with BeforeAndAfterAll {

  private implicit val system = ActorSystem("CallRecordSplitSpec")

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  "A CallRecordSplit" should {
    "merge incoming data" in {
      val testkit   = AkkaStreamletTestKit(system)
      val streamlet = new CallRecordSplit

      val instant = Instant.now.toEpochMilli / 1000
      val past    = Instant.now.minus(5000, ChronoUnit.DAYS).toEpochMilli / 1000

      val cr1 = CallRecord("user-1", "user-2", "f", 10L, instant)
      val cr2 = CallRecord("user-1", "user-2", "f", 15L, instant)
      val cr3 = CallRecord("user-1", "user-2", "f", 18L, instant)

      val source = Source(Vector(cr1, cr2, cr3))

      val in   = testkit.inletFromSource(streamlet.in, source)
      val left  = testkit.outletAsTap(streamlet.left)
      val right = testkit.outletAsTap(streamlet.right)

      testkit.run(
        streamlet,
        List(in),
        List(left, right),
        () ⇒ {
          right.probe.expectMsg(("user-1", cr1))
          right.probe.expectMsg(("user-1", cr2))
          right.probe.expectMsg(("user-1", cr3))
        }
      )

      right.probe.expectMsg(Completed)
    }

    "split incoming data into valid call records and those outside the time range" in {
      val testkit   = AkkaStreamletTestKit(system)
      val streamlet = new CallRecordSplit()

      val instant = Instant.now.toEpochMilli / 1000
      val past    = Instant.now.minus(5000, ChronoUnit.DAYS).toEpochMilli / 1000

      val cr1 = CallRecord("user-1", "user-2", "f", 10L, instant)
      val cr2 = CallRecord("user-1", "user-2", "f", 15L, instant)
      val cr3 = CallRecord("user-1", "user-2", "f", 18L, instant)
      val cr4 = CallRecord("user-1", "user-2", "f", 40L, past)
      val cr5 = CallRecord("user-1", "user-2", "f", 70L, past)

      val source = Source(Vector(cr1, cr2, cr3, cr4, cr5))

      val in = testkit.inletFromSource(streamlet.in, source)

      val left  = testkit.outletAsTap(streamlet.left)
      val right = testkit.outletAsTap(streamlet.right)

      testkit.run(
        streamlet,
        List(in),
        List(left, right),
        () ⇒ {
          right.probe.expectMsg(("user-1", cr1))
          right.probe.expectMsg(("user-1", cr2))
          right.probe.expectMsg(("user-1", cr3))
          left.probe.expectMsg((cr4.toString, InvalidRecord(cr4.toString, "Timestamp outside range!")))
          left.probe.expectMsg((cr5.toString, InvalidRecord(cr5.toString, "Timestamp outside range!")))
        }
      )

      left.probe.expectMsg(Completed)
      right.probe.expectMsg(Completed)
    }
  }
}
