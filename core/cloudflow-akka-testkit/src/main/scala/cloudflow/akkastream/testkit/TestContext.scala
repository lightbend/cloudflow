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

package cloudflow.akkastream.testkit

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent._
import akka.NotUsed
import akka.actor._
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage._
import akka.stream._
import akka.stream.scaladsl._
import com.typesafe.config._
import cloudflow.akkastream._
import cloudflow.streamlets._

import scala.concurrent.duration.{ DurationInt, FiniteDuration }

private[testkit] abstract class Completed

private[testkit] case class TestContext(
    override val streamletRef: String,
    system: ActorSystem,
    inletTaps: List[InletTap[_]],
    outletTaps: List[OutletTap[_]],
    volumeMounts: List[VolumeMount],
    override val config: Config = ConfigFactory.empty()
) extends AkkaStreamletContext {
  private val readyPromise      = Promise[Dun]()
  private val completionPromise = Promise[Dun]()
  private val completionFuture  = completionPromise.future
  val killSwitch                = KillSwitches.shared(streamletRef)
  implicit val sys              = system

  override def streamletDefinition: StreamletDefinition =
    StreamletDefinition("appId", "appVersion", streamletRef, "streamletClass", List(), volumeMounts, config)

  @deprecated("Use `sourceWithCommittableContext` instead.", "1.3.4")
  override def sourceWithOffsetContext[T](inlet: CodecInlet[T]): cloudflow.akkastream.scaladsl.SourceWithOffsetContext[T] =
    sourceWithContext(inlet)

  override def sourceWithCommittableContext[T](inlet: CodecInlet[T]) = sourceWithContext(inlet)

  private def sourceWithContext[T](inlet: CodecInlet[T]): SourceWithContext[T, CommittableOffset, _] =
    inletTaps
      .find(_.portName == inlet.name)
      .map(
        _.source
          .asInstanceOf[Source[(T, CommittableOffset), NotUsed]]
          .via(killSwitch.flow)
          .mapError {
            case cause: Throwable ⇒
              completionPromise.failure(cause)
              cause
          }
          .asSourceWithContext(_._2)
          .map(_._1)
      )
      .getOrElse(throw TestContextException(inlet.name, s"Bad test context, could not find source for inlet ${inlet.name}"))

  def shardedSourceWithCommittableContext[T, M, E](
      inlet: CodecInlet[T],
      shardEntity: Entity[M, E],
      kafkaTimeout: FiniteDuration = 10.seconds
  ): SourceWithContext[T, CommittableOffset, Future[NotUsed]] = {
    ClusterSharding(system.toTyped).init(shardEntity)

    Source
      .futureSource(
        Future {
          sourceWithContext(inlet).asSource
            .asInstanceOf[Source[(T, CommittableOffset), NotUsed]]
        }(system.dispatcher)
      )
      .asSourceWithContext { case (_, committableOffset) ⇒ committableOffset }
      .map { case (record, _) ⇒ record }

  }

  private def flowWithCommittableContext[T](outlet: CodecOutlet[T]): cloudflow.akkastream.scaladsl.FlowWithCommittableContext[T, T] = {
    val flow = Flow[T]

    outletTaps
      .find(_.portName == outlet.name)
      .map { outletTap ⇒
        val tout = outletTap.asInstanceOf[OutletTap[T]]
        flow
          .via(killSwitch.flow)
          .mapError {
            case cause: Throwable ⇒
              completionPromise.failure(cause)
              cause
          }
          .alsoTo(
            Flow[T].map(t ⇒ tout.toPartitionedValue(t)).to(tout.sink)
          )
          .asFlowWithContext[T, Committable, Committable]((el, _) ⇒ el)(_ ⇒ TestCommittableOffset())
      }
      .getOrElse(throw TestContextException(outlet.name, s"Bad test context, could not find sink for outlet ${outlet.name}"))
  }
  def committableSink[T](committerSettings: CommitterSettings): Sink[(T, Committable), NotUsed] =
    Flow[(T, Committable)].toMat(Sink.ignore)(Keep.left)
  def committableSink[T](outlet: CodecOutlet[T], committerSettings: CommitterSettings): Sink[(T, Committable), NotUsed] =
    flowWithCommittableContext[T](outlet).asFlow.toMat(Sink.ignore)(Keep.left)

  @deprecated("Use `committableSink` instead.", "1.3.1")
  def sinkWithOffsetContext[T](committerSettings: CommitterSettings): Sink[(T, CommittableOffset), NotUsed] =
    Flow[(T, Committable)].toMat(Sink.ignore)(Keep.left)

  @deprecated("Use `committableSink` instead.", "1.3.1")
  def sinkWithOffsetContext[T](outlet: CodecOutlet[T], committerSettings: CommitterSettings): Sink[(T, CommittableOffset), NotUsed] =
    flowWithCommittableContext[T](outlet).asFlow.toMat(Sink.ignore)(Keep.left)

  def plainSource[T](inlet: CodecInlet[T], resetPosition: ResetPosition): Source[T, NotUsed] =
    sourceWithCommittableContext[T](inlet).asSource.map(_._1).mapMaterializedValue(_ ⇒ NotUsed)

  def shardedPlainSource[T, M, E](inlet: CodecInlet[T],
                                  shardEntity: Entity[M, E],
                                  resetPosition: ResetPosition = Latest,
                                  kafkaTimeout: FiniteDuration = 10.seconds): Source[T, Future[NotUsed]] = {
    ClusterSharding(system.toTyped).init(shardEntity)
    Source.futureSource(
      Future {
        plainSource(inlet, resetPosition)
      }(system.dispatcher)
    )
  }

  def plainSink[T](outlet: CodecOutlet[T]): Sink[T, NotUsed] = sinkRef[T](outlet).sink.contramap { el ⇒
    (el, TestCommittableOffset())
  }
  def sinkRef[T](outlet: CodecOutlet[T]): WritableSinkRef[T] =
    new WritableSinkRef[T] {
      def sink = {
        val flow = Flow[(T, Committable)]
        outletTaps
          .find(_.portName == outlet.name)
          .map { tap ⇒
            val outletTap = tap.asInstanceOf[OutletTap[T]]
            flow
              .map { case (t, _) ⇒ outletTap.toPartitionedValue(t) }
              .via(killSwitch.flow)
              .mapError {
                case cause: Throwable ⇒
                  completionPromise.failure(cause)
                  cause
              }
              .to(outletTap.sink)
          }
          .getOrElse(throw TestContextException(outlet.name, s"Bad test context, could not find sink for outlet ${outlet.name}"))
      }

      def write(value: T): Future[T] = {
        Source.single(value).runWith(sink.contramap[T](t ⇒ (t, TestCommittableOffset())))
        Future.successful(value)
      }
    }

  def streamletExecution: StreamletExecution = new StreamletExecution() {
    val readyFuture            = readyPromise.future
    def completed: Future[Dun] = completionFuture
    def ready: Future[Dun]     = readyFuture
    def stop(): Future[Dun]    = TestContext.this.stop()
  }

  private val stoppers = new AtomicReference(Vector.empty[() ⇒ Future[Dun]])

  def onStop(f: () ⇒ Future[Dun]): Unit =
    stoppers.getAndUpdate(old ⇒ old :+ f)

  def signalReady(): Boolean = readyPromise.trySuccess(Dun)

  def stop(): Future[Dun] = {
    killSwitch.shutdown()
    import system.dispatcher
    Future
      .sequence(
        stoppers.get.map { f ⇒
          f().recover {
            case _ ⇒ Dun
          }
        }
      )
      .flatMap { _ ⇒
        completionPromise.trySuccess(Dun)
        completionFuture
      }
  }

  def metricTags(): Map[String, String] =
    Map()
}

case class TestContextException(portName: String, msg: String) extends RuntimeException(msg)

import akka.kafka.ConsumerMessage._
object TestCommittableOffset {
  def apply(): CommittableOffset =
    akka.kafka.testkit.ConsumerResultFactory.committableOffset(PartitionOffset(GroupTopicPartition("", "", 0), 0L), "")
}
