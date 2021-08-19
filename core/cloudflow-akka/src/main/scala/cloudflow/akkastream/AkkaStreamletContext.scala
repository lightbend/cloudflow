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

package cloudflow.akkastream

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.immutable
import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.kafka.ConsumerMessage.{ Committable, CommittableOffset }
import akka.kafka.CommitterSettings
import akka.stream.KillSwitches
import akka.stream.scaladsl._
import cloudflow.streamlets._

import scala.concurrent.duration.{ DurationInt, FiniteDuration }

/**
 * Runtime context for [[AkkaStreamlet]]s, which provides means to create [[akka.stream.scaladsl.Source Source]]s and [[akka.stream.scaladsl.Sink Sink]]s respectively
 * for [[cloudflow.streamlets.CodecInlet CodeInlet]]s and [[cloudflow.streamlets.CodecOutlet CodeOutlet]]s.
 * The StreamletContext also contains some lifecycle hooks, like `signalReady`, `onStop` and `stop`
 * and provides access to the streamlet configuration.
 * It also provides the [[akka.actor.ActorSystem ActorSystem]] and [[akka.stream.Materializer Materializer]] that will be used to run the AkkaStreamlet.
 */
trait AkkaStreamletContext extends StreamletContext {

  private[akkastream] def sourceWithCommittableContext[T](
      inlet: CodecInlet[T]): cloudflow.akkastream.scaladsl.SourceWithCommittableContext[T]

  private[akkastream] def shardedSourceWithCommittableContext[T, M, E](
      inlet: CodecInlet[T],
      shardEntity: Entity[M, E],
      kafkaTimeout: FiniteDuration = 10.seconds): SourceWithContext[T, CommittableOffset, Future[NotUsed]]

  @deprecated("Use `sourceWithCommittableContext` instead.", "1.3.4")
  private[akkastream] def sourceWithOffsetContext[T](
      inlet: CodecInlet[T]): cloudflow.akkastream.scaladsl.SourceWithOffsetContext[T]

  private[akkastream] def plainSource[T](inlet: CodecInlet[T], resetPosition: ResetPosition): Source[T, NotUsed]
  private[akkastream] def plainSink[T](outlet: CodecOutlet[T]): Sink[T, NotUsed]
  private[akkastream] def shardedPlainSource[T, M, E](
      inlet: CodecInlet[T],
      shardEntity: Entity[M, E],
      resetPosition: ResetPosition = Latest,
      kafkaTimeout: FiniteDuration = 10.seconds): Source[T, Future[NotUsed]]

  private[akkastream] def committableSink[T](
      outlet: CodecOutlet[T],
      committerSettings: CommitterSettings): Sink[(T, Committable), NotUsed]
  private[akkastream] def committableSink[T](committerSettings: CommitterSettings): Sink[(T, Committable), NotUsed]

  private[akkastream] def flexiFlow[T](
      outlet: CodecOutlet[T]): Flow[(immutable.Seq[_ <: T], _ <: Committable), (Unit, Committable), NotUsed]

  @deprecated("Use `committableSink` instead.", "1.3.4")
  private[akkastream] def sinkWithOffsetContext[T](
      outlet: CodecOutlet[T],
      committerSettings: CommitterSettings): Sink[(T, CommittableOffset), NotUsed]
  @deprecated("Use `committableSink` instead.", "1.3.4")
  private[akkastream] def sinkWithOffsetContext[T](
      committerSettings: CommitterSettings): Sink[(T, CommittableOffset), NotUsed]

  /**
   * Creates a [[akka.stream.SinkRef SinkRef]] to write to, for the specified [[cloudflow.streamlets.CodecOutlet CodecOutlet]]
   *
   * @param outlet the specified [[cloudflow.streamlets.CodecOutlet CodecOutlet]]
   * @return the [[cloudflow.akkastream.WritableSinkRef WritableSinkRef]] created
   */
  private[akkastream] def sinkRef[T](outlet: CodecOutlet[T]): WritableSinkRef[T]

  /**
   * The system in which the AkkaStreamlet will be run.
   */
  implicit def system: ActorSystem
  protected val killSwitch = KillSwitches.shared(streamletRef)

  private[akkastream] def streamletExecution: StreamletExecution

  @InternalApi
  private[akkastream] object Stoppers {

    private val stoppers = new AtomicReference(Vector.empty[() => Future[Dun]])

    def add(f: () => Future[Dun]): Unit = stoppers.getAndUpdate(old => old :+ f)

    def stop(): Future[Done] = {
      implicit val ec: ExecutionContext = system.dispatcher
      Future
        .sequence(stoppers.get.map { f =>
          f().recover {
            case cause =>
              system.log.error(cause, "onStop callback failed.")
              Dun
          }
        })
        .map(_ => Done)
    }
  }

  /**
   * Signals that the streamlet is ready to process data.
   *
   * When a streamlet is run using `AkkaStreamletTestkit.run`, a [[cloudflow.streamlets.StreamletExecution StreamletExecution]] is returned.
   * `signalReady` completes the [[cloudflow.streamlets.StreamletExecution#ready ready]] future.
   * [[cloudflow.streamlets.StreamletExecution#ready ready]] can be used for instance to wait
   * for a [[cloudflow.akkastream.Server Server]] to signal that it is ready to accept requests.
   *
   * @return {@code true} if and only if successfully signalled. Otherwise {@code false}.
   */
  def signalReady(): Boolean

  /**
   * Marks the streamlet pod "ready" for Kubernetes.
   */
  def ready(localMode: Boolean): Unit

  /**
   * Marks the streamlet pod "alive" for Kubernetes.
   */
  def alive(localMode: Boolean): Unit

  /**
   * Stops the streamlet knowing an exception occured.
   */
  def stopOnException(nonFatal: Throwable): Unit

  /**
   * Stops the streamlet.
   */
  def stop(): Future[Dun]

  /**
   * Registers a callback, which is called when the streamlet is stopped.
   * It is usually used to close resources that have been created in the streamlet.
   */
  def onStop(f: () => Future[Dun]): Unit = Stoppers.add(f)

  private[akkastream] def metricTags(): Map[String, String]
}

/**
 * The position to initially start reading from, when using `plainSource`.
 *
 * Maps to the "auto.offset.reset" Kafka setting with `autoOffsetReset`.
 */
sealed trait ResetPosition {
  def autoOffsetReset: String
}

/**
 * Automatically reset the offset to the earliest offset.
 */
case object Earliest extends ResetPosition {
  val autoOffsetReset = "earliest"
}

/**
 * Automatically reset the offset to the latest offset.
 */
case object Latest extends ResetPosition {
  val autoOffsetReset = "latest"
}
