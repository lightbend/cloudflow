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

package cloudflow.akkastream.testkit.javadsl

import akka.NotUsed
import akka.kafka.ConsumerMessage._
import akka.stream._
import akka.stream.scaladsl._

import cloudflow.streamlets._
import cloudflow.akkastream.testkit._

// The use of Tuple here is OK since the creation of the tuple is handled
// internally by the AkkaStreamletTestKit when creating instances of this class
case class SourceInletTap[T] private[testkit] (inlet: CodecInlet[T], src: akka.stream.javadsl.Source[(T, Committable), NotUsed]) extends InletTap[T] {
  val portName = inlet.name

  private[testkit] val source = src.asScala
}

case class QueueInletTap[T](inlet: CodecInlet[T])(implicit mat: ActorMaterializer) extends InletTap[T] {
  private val bufferSize = 1024
  private val hub = BroadcastHub.sink[T](bufferSize)

  // Here we map the materialized value of the Scala queue source to materialize
  // to the Javadsl version of `SourceQueueWithComplete` so Java users can use
  // the `offer` method with native CompletionStages instead of Scala Futures.
  //
  // It is still a `scaladsl.Source[T, SourceQueueWithComplete]` because it will
  // only be used by lower-level Scala code. But the resulting materialized value,
  // e.g. the `SourceQueueWithComplete` is now the `javadsl.SourceQueueWithComplete`.
  private val qSource =
    Source
      .queue[T](bufferSize, OverflowStrategy.backpressure)
      .mapMaterializedValue(new SourceQueueAdapter(_))

  private[testkit] val (q, src) = qSource.toMat(hub)(Keep.both).run()

  val portName = inlet.name
  val source = src.map { t ⇒ (t, TestCommittableOffset()) }
  val queue: akka.stream.javadsl.SourceQueueWithComplete[T] = q
}

/**
 * Copied over from Akka internals (akka.stream.impl.QueueSource.scala, 2.5.23)
 */
private[testkit] final class SourceQueueAdapter[T](delegate: SourceQueueWithComplete[T])
  extends akka.stream.javadsl.SourceQueueWithComplete[T] {
  import java.util.concurrent.CompletionStage
  import scala.compat.java8.FutureConverters._
  import akka.Done

  def offer(elem: T): CompletionStage[QueueOfferResult] = delegate.offer(elem).toJava
  def watchCompletion(): CompletionStage[Done] = delegate.watchCompletion().toJava
  def complete(): Unit = delegate.complete()
  def fail(ex: Throwable): Unit = delegate.fail(ex)
}
