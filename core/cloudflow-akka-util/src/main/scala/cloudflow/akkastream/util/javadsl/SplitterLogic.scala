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

package cloudflow.akkastream.util.javadsl

import akka.NotUsed
import akka.stream._
import akka.stream.javadsl._
import akka.kafka.ConsumerMessage._
import cloudflow._
import cloudflow.akkastream._
import cloudflow.akkastream.javadsl._
import cloudflow.akkastream.javadsl.util.{ Either ⇒ JEither }
import cloudflow.streamlets._

object Splitter {
  def graph[I, L, R](
      flow: FlowWithCommittableContext[I, Either[L, R]],
      left: Sink[(L, Committable), NotUsed],
      right: Sink[(R, Committable), NotUsed]
  ): Graph[akka.stream.SinkShape[(I, Committable)], NotUsed] = akkastream.util.scaladsl.Splitter.graph[I, L, R](flow.asScala, left.asScala, right.asScala)

  def sink[I, L, R](
      flow: FlowWithCommittableContext[I, Either[L, R]],
      left: Sink[(L, Committable), NotUsed],
      right: Sink[(R, Committable), NotUsed]
  ): Sink[(I, Committable), NotUsed] = akkastream.util.scaladsl.Splitter.sink[I, L, R](flow.asScala, left.asScala, right.asScala).asJava
}

@deprecated("Use `Splitter.sink` instead.", "1.3.1")
abstract class SplitterLogic[I, L, R](
    in: CodecInlet[I],
    left: CodecOutlet[L],
    right: CodecOutlet[R],
    context: AkkaStreamletContext
) extends akkastream.util.scaladsl.SplitterLogic(in, left, right)(context) {

  def createFlow(): FlowWithOffsetContext[I, JEither[L, R]]
  def flow: cloudflow.akkastream.scaladsl.FlowWithOffsetContext[I, Either[L, R]] = {
    createFlow().map(jEither ⇒ if (jEither.isRight) Right(jEither.get()) else Left(jEither.getLeft())).asScala
  }
  final def createFlowWithOffsetContext() = FlowWithOffsetContext.create[I]()
}
