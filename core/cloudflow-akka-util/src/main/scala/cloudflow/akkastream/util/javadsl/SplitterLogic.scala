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
import akka.japi.Pair
import akka.kafka._
import akka.stream.javadsl._
import akka.kafka.ConsumerMessage._
import akka.stream.scaladsl
import cloudflow._
import cloudflow.akkastream._
import cloudflow.akkastream.javadsl._
import cloudflow.akkastream.javadsl.util.{ Either => JEither }
import cloudflow.akkastream.scaladsl.FlowWithCommittableContext
import cloudflow.streamlets._

/**
 * Java API
 * Provides functions to split elements based on a flow of type `FlowWithCommittableContext[I, Either[L, R]]`.
 */
object Splitter {

  /**
   * Java API
   * A Sink that splits elements based on a flow of type `FlowWithCommittableContext[I, Either[L, R]]`.
   * At-least-once semantics are used.
   */
  @deprecated("prefer providing Outlets, this variant can't guarantee at-least-once", "2.10.12")
  def sink[I, L, R](
      flow: FlowWithContext[I, Committable, JEither[L, R], Committable, NotUsed],
      left: Sink[Pair[L, Committable], NotUsed],
      right: Sink[Pair[R, Committable], NotUsed]
  ): Sink[Pair[I, Committable], NotUsed] =
    akkastream.util.scaladsl.Splitter
      .sink[I, L, R](
        flow.via(toEitherFlow).asScala,
        left.contramap[Tuple2[L, Committable]] { case (t, c)  ⇒ new Pair(t, c) }.asScala,
        right.contramap[Tuple2[R, Committable]] { case (t, c) ⇒ new Pair(t, c) }.asScala
      )
      .contramap[Pair[I, Committable]] { pair ⇒
        (pair.first, pair.second)
      }
      .asJava

  /**
   * Java API
   * A Sink that splits elements based on a flow of type `FlowWithCommittableContext[I, Either[L, R]]`.
   * At-least-once semantics are used.
   */
  def sink[I, L, R](
      flow: FlowWithContext[I, Committable, JEither[L, R], Committable, NotUsed],
      leftOutlet: CodecOutlet[L],
      rightOutlet: CodecOutlet[R],
      committerSettings: CommitterSettings,
      context: AkkaStreamletContext
  ): Sink[Pair[I, Committable], NotUsed] =
    akka.stream.javadsl.Flow
      .create[Pair[I, Committable]]()
      .map(_.toScala)
      .to(
        akkastream.util.scaladsl.Splitter
          .sink[I, L, R](
            flow.via(toEitherFlow).asScala,
            leftOutlet,
            rightOutlet,
            committerSettings
          )(context)
      )

  /**
   * Java API
   * A Sink that splits elements based on a flow of type `FlowWithCommittableContext[I, Either[L, R]]`.
   * At-least-once semantics are used.
   */
  def sink[I, L, R](
      flow: FlowWithContext[I, Committable, JEither[L, R], Committable, NotUsed],
      leftOutlet: CodecOutlet[L],
      rightOutlet: CodecOutlet[R],
      context: AkkaStreamletContext
  ): Sink[Pair[I, Committable], NotUsed] =
    sink[I, L, R](flow, leftOutlet, rightOutlet, CommitterSettings(context.system), context)

  private def toEitherFlow[L, R] =
    FlowWithContext
      .create[JEither[L, R], Committable]()
      .map(jEither ⇒ if (jEither.isRight) Right(jEither.get()) else Left(jEither.getLeft()))
}

@deprecated("Use `Splitter.sink` instead.", "1.3.1")
abstract class SplitterLogic[I, L, R](
    in: CodecInlet[I],
    left: CodecOutlet[L],
    right: CodecOutlet[R],
    context: AkkaStreamletContext
) extends akkastream.util.scaladsl.SplitterLogic(in, left, right)(context) {

  def createFlow(): FlowWithContext[I, Committable, JEither[L, R], CommittableOffset, NotUsed]
  def flow: cloudflow.akkastream.scaladsl.FlowWithOffsetContext[I, Either[L, R]] =
    createFlow().map(jEither ⇒ if (jEither.isRight) Right(jEither.get()) else Left(jEither.getLeft())).asScala
  final def createFlowWithOffsetContext() = FlowWithOffsetContext.create[I]()
}
