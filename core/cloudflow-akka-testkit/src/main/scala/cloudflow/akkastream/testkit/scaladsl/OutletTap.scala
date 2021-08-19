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

package cloudflow.akkastream.testkit.scaladsl

import scala.concurrent._

import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.testkit.TestKit

import cloudflow.streamlets._
import cloudflow.akkastream.testkit.PartitionedValue

case class Failed(e: Throwable)

case class SinkOutletTap[T](outlet: CodecOutlet[T], val snk: Sink[(String, T), NotUsed]) extends OutletTap[T] {
  private[testkit] val flow: Flow[PartitionedValue[T], PartitionedValue[T], NotUsed] =
    Flow[PartitionedValue[T]]
      .alsoTo(
        Flow[PartitionedValue[T]]
          .map(pv => (pv.key, pv.value))
          .to(snk))

  private[testkit] val sink: Sink[PartitionedValue[T], Future[Done]] =
    flow.toMat(Sink.ignore)(Keep.right)
}

case class ProbeOutletTap[T](outlet: CodecOutlet[T])(implicit system: ActorSystem) extends OutletTap[T] {
  val probe = new TestKit(system)

  private[testkit] val flow: Flow[PartitionedValue[T], PartitionedValue[T], NotUsed] =
    Flow[PartitionedValue[T]]
      .alsoTo(
        Flow[PartitionedValue[T]]
          .map(pv => (pv.key, pv.value))
          .to(Sink.actorRef[Tuple2[String, T]](probe.testActor, Completed, Failed)))

  // This will emit Tuple2 elements to the test actor (partitioning key -> data)
  // for easy usage in Scala-based tests
  private[testkit] val sink: Sink[PartitionedValue[T], Future[Done]] =
    flow.toMat(Sink.ignore)(Keep.right)
}
