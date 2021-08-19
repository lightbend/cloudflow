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

package cloudflow.akkastream.util.scaladsl

import scala.collection.immutable

import akka._
import akka.kafka._
import akka.stream._
import akka.stream.contrib._
import akka.stream.scaladsl._
import akka.kafka.ConsumerMessage._
import cloudflow.streamlets._
import cloudflow.akkastream._
import cloudflow.akkastream.internal.MultiProducer
import cloudflow.akkastream.scaladsl._

/**
 * Provides functions to split elements based on a flow of type `FlowWithCommittableContext[I, Either[L, R]]`.
 */
object Splitter {

  /**
   * A Graph that splits elements based on a flow of type `FlowWithCommittableContext[I, Either[L, R]]`.
   */
  @deprecated("prefer providing Outlets, this variant can't guarantee at-least-once", "2.10.12")
  def graph[I, L, R](
      flow: FlowWithCommittableContext[I, Either[L, R]],
      left: Sink[(L, Committable), NotUsed],
      right: Sink[(R, Committable), NotUsed]): Graph[akka.stream.SinkShape[(I, Committable)], NotUsed] =
    GraphDSL.create(left, right)(Keep.left) { implicit builder: GraphDSL.Builder[NotUsed] => (il, ir) =>
      import GraphDSL.Implicits._

      val toEitherFlow = builder.add(flow.asFlow)
      val partitionWith = PartitionWith[(Either[L, R], Committable), (L, Committable), (R, Committable)] {
        case (Left(e), offset)  => Left((e, offset))
        case (Right(e), offset) => Right((e, offset))
      }
      val partitioner = builder.add(partitionWith)

      // format: OFF
        toEitherFlow ~> partitioner.in
                        partitioner.out0 ~> il
                        partitioner.out1 ~> ir
      // format: ON

      SinkShape(toEitherFlow.in)
    }

  /**
   * A Sink that splits elements based on a flow of type `FlowWithCommittableContext[I, Either[L, R]]`.
   * At-least-once semantics are used.
   */
  @deprecated("prefer providing Outlets, this variant can't guarantee at-least-once", "2.10.12")
  def sink[I, L, R](
      flow: FlowWithCommittableContext[I, Either[L, R]],
      left: Sink[(L, Committable), NotUsed],
      right: Sink[(R, Committable), NotUsed]): Sink[(I, Committable), NotUsed] =
    Sink.fromGraph(graph(flow, left, right))

  /**
   * A Sink that splits elements based on a flow of type `FlowWithCommittableContext[I, Either[L, R]]`.
   * At-least-once semantics are used.
   */
  def sink[I, L, R](
      flow: FlowWithCommittableContext[I, Either[L, R]],
      leftOutlet: CodecOutlet[L],
      rightOutlet: CodecOutlet[R])(implicit context: AkkaStreamletContext): Sink[(I, Committable), NotUsed] = {
    val defaultSettings = CommitterSettings(context.system)
    sink[I, L, R](flow, leftOutlet, rightOutlet, defaultSettings)
  }

  /**
   * A Sink that splits elements based on a flow of type `FlowWithCommittableContext[I, Either[L, R]]`.
   * At-least-once semantics are used.
   */
  def sink[I, L, R](
      flow: FlowWithCommittableContext[I, Either[L, R]],
      leftOutlet: CodecOutlet[L],
      rightOutlet: CodecOutlet[R],
      committerSettings: CommitterSettings)(implicit context: AkkaStreamletContext): Sink[(I, Committable), NotUsed] =
    flow
      .map(MultiData2.fromEither(_))
      .asFlow
      .to(MultiProducer.sink2(leftOutlet, rightOutlet, committerSettings))
}

/**
 * A StreamletLogic that splits elements based on a flow of type `FlowWithOffsetContext[I, Either[L, R]]`.
 */
@deprecated("Use `Splitter.sink` instead.", "1.3.1")
abstract class SplitterLogic[I, L, R](inlet: CodecInlet[I], leftOutlet: CodecOutlet[L], rightOutlet: CodecOutlet[R])(
    implicit context: AkkaStreamletContext)
    extends RunnableGraphStreamletLogic()(context) {

  /**
   * Defines the flow that receives elements from the inlet.
   * The offset associated with every output element is automatically committed using at-least-once semantics.
   */
  @deprecated("Use `Splitter.sink` instead.", "1.3.1")
  def flow: FlowWithOffsetContext[I, Either[L, R]]

  @deprecated("Use `Splitter.sink` instead.", "1.3.1")
  final def flowWithOffsetContext() = FlowWithOffsetContext[I]

  /**
   * Implements at-least-once semantics while reading from inlet and
   * writing to the outlet
   */
  override def runnableGraph() = {
    val in = sourceWithOffsetContext[I](inlet)
    val left = committableSink[L](leftOutlet)
    val right = committableSink[R](rightOutlet)

    val splitterGraph = RunnableGraph.fromGraph(GraphDSL.create(left, right)(Keep.left) {
      implicit builder: GraphDSL.Builder[NotUsed] => (il, ir) =>
        import GraphDSL.Implicits._

        val toEitherFlow = builder.add(flow.asFlow)
        val partitionWith = PartitionWith[(Either[L, R], Committable), (L, Committable), (R, Committable)] {
          case (Left(e), offset)  => Left((e, offset))
          case (Right(e), offset) => Right((e, offset))
        }
        val partitioner = builder.add(partitionWith)

        // format: OFF
        in ~> toEitherFlow ~> partitioner.in
                              partitioner.out0 ~> il
                              partitioner.out1 ~> ir
        // format: ON

        ClosedShape
    })
    splitterGraph
  }
}
