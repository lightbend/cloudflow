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

import akka._
import akka.stream._
import akka.stream.contrib._
import akka.stream.scaladsl._
import akka.kafka.ConsumerMessage._
import cloudflow.streamlets._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._

/**
 * A StreamletLogic that splits elements based on a flow of type `FlowWithOffsetContext[I, Either[L, R]]`.
 */
abstract class SplitterLogicAkka[I, L, R](
    inlet: CodecInlet[I],
    leftOutlet: CodecOutlet[L],
    rightOutlet: CodecOutlet[R]
)(implicit context: AkkaStreamletContext) extends RunnableGraphAkkaStreamletLogic()(context) {
  /**
   * Defines the flow that receives elements from the inlet.
   * The offset associated with every output element is automatically committed using at-least-once semantics.
   */
  def flow: FlowWithOffsetContext[I, Either[L, R]]

  final def flowWithOffsetContext() = FlowWithOffsetContext[I]

  /**
   * Implements at-least-once semantics while reading from inlet and
   * writing to the outlet
   */
  override def runnableGraph() = {
    val in = sourceWithOffsetContext[I](inlet)
    val left = sinkWithOffsetContext[L](leftOutlet)
    val right = sinkWithOffsetContext[R](rightOutlet)

    val splitterGraph = RunnableGraph.fromGraph(
      GraphDSL.create(left, right)(Keep.left) { implicit builder: GraphDSL.Builder[NotUsed] ⇒ (il, ir) ⇒
        import GraphDSL.Implicits._

        val toEitherFlow = builder.add(flow.asFlow)
        val partitionWith = PartitionWith[(Either[L, R], CommittableOffset), (L, CommittableOffset), (R, CommittableOffset)] {
          case (Left(e), offset)  ⇒ Left((e, offset))
          case (Right(e), offset) ⇒ Right((e, offset))
        }
        val partitioner = builder.add(partitionWith)

        // format: OFF
        in ~> toEitherFlow ~> partitioner.in
                              partitioner.out0 ~> il
                              partitioner.out1 ~> ir
        // format: ON

        ClosedShape
      }
    )
    splitterGraph
  }
}
