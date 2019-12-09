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

import scala.collection.immutable

import akka.NotUsed
import akka.stream.ClosedShape
import akka.stream.scaladsl._

import cloudflow.akkastream._
import cloudflow.streamlets._
import cloudflow.akkastream.scaladsl._
import akka.kafka.ConsumerMessage._

/**
 * A `MergeLogic` merges two or more inlets into one outlet.
 * Elements from all inlets will be processed with at-least-once semantics. The elements will be processed
 * in semi-random order and with equal priority for all inlets.
 */
class MergeLogicAkka[T](
    inletPorts: immutable.IndexedSeq[CodecInlet[T]],
    outlet: CodecOutlet[T]
)(implicit context: AkkaStreamletContext) extends RunnableGraphAkkaStreamletLogic {
  require(inletPorts.size >= 2)
  final def flowWithCommittableOffset() = FlowWithOffsetContext[T]

  /**
   * The graph is built from input elements using at-least-once processing semantics
   */
  override def runnableGraph() = {

    val inlets = inletPorts.map(inlet ⇒ sourceWithOffsetContext[T](inlet)).toList
    val out = sinkWithOffsetContext[T](outlet)

    RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] ⇒
      import GraphDSL.Implicits._
      val merge = builder.add(Merge[(T, CommittableOffset)](inletPorts.size))
      val head :: tail = inlets
      head ~> merge ~> out
      tail.foreach(inlet ⇒ inlet ~> merge)
      ClosedShape
    })
  }
}
