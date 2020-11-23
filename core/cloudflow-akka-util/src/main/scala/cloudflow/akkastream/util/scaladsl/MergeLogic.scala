/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
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

import akka.stream._
import akka.stream.ClosedShape
import akka.stream.scaladsl._

import cloudflow.akkastream._
import cloudflow.streamlets._
import cloudflow.akkastream.scaladsl._
import akka.kafka.ConsumerMessage._

/**
 * Merges two or more sources, or inlets, of the same type, into one source.
 */
object Merger {

  /**
   * Creates a graph to merge two or more sources into one source.
   * Elements from all sources will be processed with at-least-once semantics. The elements will be processed
   * in semi-random order and with equal priority for all sources.
   */
  def graph[T](
      sources: Seq[SourceWithContext[T, Committable, _]]
  ): Graph[SourceShape[(T, Committable)], NotUsed] =
    GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
      import GraphDSL.Implicits._
      val merge = builder.add(Merge[(T, Committable)](sources.size))
      sources.foreach(inlet => inlet ~> merge)
      SourceShape[(T, Committable)](merge.out)
    }

  /**
   * Merges two or more sources into one source.
   * Elements from all inlets will be processed with at-least-once semantics. The elements will be processed
   * in semi-random order and with equal priority for all sources.
   */
  def source[T](
      sources: Seq[SourceWithContext[T, Committable, _]]
  ): SourceWithContext[T, Committable, _] =
    Source.fromGraph(graph(sources)).asSourceWithContext { case (_, offset) => offset }.map { case (t, _) => t }

  /**
   * Merges two or more inlets into one source.
   * Elements from all inlets will be processed with at-least-once semantics. The elements will be processed
   * in semi-random order and with equal priority for all inlets.
   */
  def source[T](
      inlets: Seq[CodecInlet[T]],
      dataconverter: InletDataConverter[T] = DefaultInletDataConverter[T]
  )(implicit context: AkkaStreamletContext): SourceWithContext[T, Committable, _] =
    Source
      .fromGraph(graph(inlets.map(context.sourceWithCommittableContext(_, dataconverter))))
      .asSourceWithContext { case (_, offset) => offset }
      .map { case (t, _) => t }

  /*
  This method requires dataconverter, but can't add it here
  def source[T](
      inlet: CodecInlet[T],
      inlets: CodecInlet[T]*
  )(implicit context: AkkaStreamletContext): SourceWithContext[T, Committable, _] =
    Source
      .fromGraph(graph((inlet +: inlets.toList).map(context.sourceWithCommittableContext(_))))
      .asSourceWithContext { case (_, offset) => offset }
      .map { case (t, _) => t }
 */
}

/**
 * A `MergeLogic` merges two or more inlets into one outlet.
 * Elements from all inlets will be processed with at-least-once semantics. The elements will be processed
 * in semi-random order and with equal priority for all inlets.
 */
@deprecated("Use `Merger.source` instead.", "1.3.1")
class MergeLogic[T](
    inletPorts: immutable.IndexedSeq[CodecInlet[T]],
    outlet: CodecOutlet[T]
)(implicit context: AkkaStreamletContext)
    extends RunnableGraphStreamletLogic {
  require(inletPorts.size >= 2)

  /**
   * The graph is built from input elements using at-least-once processing semantics
   */
  override def runnableGraph() = {

    val inlets = inletPorts.map(inlet => sourceWithCommittableContext[T](inlet)).toList
    val out    = committableSink[T](outlet)

    RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
      import GraphDSL.Implicits._
      val merge = builder.add(Merger.graph(inlets))
      merge ~> out
      ClosedShape
    })
  }
}
