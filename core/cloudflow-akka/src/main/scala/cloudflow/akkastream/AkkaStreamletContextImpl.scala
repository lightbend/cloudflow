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

package cloudflow.akkastream

import java.util.concurrent.atomic.AtomicReference

import java.nio.file.{ Paths, Files }

import scala.concurrent._
import scala.util._

import akka._
import akka.actor.ActorSystem
import akka.kafka._
import akka.kafka.ConsumerMessage._
import akka.kafka.scaladsl._
import akka.stream._
import akka.stream.scaladsl._

import com.typesafe.config._

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization._

import cloudflow.streamlets._
import scala.util.Random

final case class PortNotFoundException(port: StreamletPort, context: StreamletContext)
  extends RuntimeException(s"Port ${port.name} not found in context $context.")

object AkkaStreamletContextImpl {
  def apply(streamletDefinition: StreamletDefinition): AkkaStreamletContextImpl =
    new AkkaStreamletContextImpl(streamletDefinition)
}

/**
 * Implementation of the StreamletContext trait.
 */
final class AkkaStreamletContextImpl(
    private[cloudflow] override val streamletDefinition: StreamletDefinition
) extends AkkaStreamletContext {
  implicit val system: ActorSystem = ActorSystem("akka_streamlet", config)

  implicit def materializer = ActorMaterializer()(system)

  override def config: Config = streamletDefinition.config

  private val readyPromise = Promise[Dun]()
  private val completionPromise = Promise[Dun]()
  private val completionFuture = completionPromise.future

  val killSwitch = KillSwitches.shared(streamletRef)

  val streamletExecution = new StreamletExecution() {
    val readyFuture = readyPromise.future
    def completed: Future[Dun] = completionFuture
    def ready: Future[Dun] = readyFuture
    def stop(): Future[Dun] = AkkaStreamletContextImpl.this.stop()
  }

  private val generator = Random

  private val bootstrapServers = system.settings.config.getString("cloudflow.kafka.bootstrap-servers")
  private def groupId[T](savepointPath: SavepointPath, streamletRef: String, inlet: CodecInlet[T]) = {
    val base = s"${savepointPath.appId}.${streamletRef}.${inlet.name}"
    inlet.readFromAllPartitions match {
      case true ⇒ base + generator.nextInt.toString
      case _    ⇒ base
    }
  }

  def sourceWithOffsetContext[T](inlet: CodecInlet[T]): cloudflow.akkastream.scaladsl.SourceWithOffsetContext[T] = {
    val savepointPath = findSavepointPathForPort(inlet)
    val topic = savepointPath.value
    val gId = groupId(savepointPath, streamletRef, inlet)
    val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(gId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    system.log.info(s"Creating committable source for group: $gId topic: $topic")

    Consumer.sourceWithOffsetContext(consumerSettings, Subscriptions.topics(topic))
      // TODO clean this up, once SourceWithContext has mapError and mapMaterializedValue
      .asSource
      .mapMaterializedValue(_ ⇒ NotUsed) // TODO we should likely use control to gracefully stop.
      .via(handleTermination)
      .map {
        case (record, committableOffset) ⇒ inlet.codec.decode(record.value) -> committableOffset
      }.asSourceWithContext { case (_, committableOffset) ⇒ committableOffset }.map { case (record, _) ⇒ record }
  }

  // TODO the Out type of the flow is not correct yet. This needs some work, should it be the Alpakka Kafka Result, or the original message sent?
  // TODO this might be removed, and only provide sinkWithOffsetContext (as Enno is now building it) to prevent unnecessary back-pressure issues.
  def flowWithOffsetContext[T](outlet: CodecOutlet[T]): cloudflow.akkastream.scaladsl.FlowWithOffsetContext[T, _] = {
    val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
      .withBootstrapServers(bootstrapServers)
    val savepointPath = findSavepointPathForPort(outlet)
    val topic = savepointPath.value
    // TODO this can be made simpler if mapError would be available on FlowWithContext
    FlowWithContext.fromTuples(Flow[(T, CommittableOffset)]
      .map {
        case (value, offset) ⇒
          val key = outlet.partitioner(value)
          val bytesKey = keyBytes(key)
          val bytesValue = outlet.codec.encode(value)
          (ProducerMessage.single(new ProducerRecord(topic, bytesKey, bytesValue)), offset)
      }
      .via(handleTermination)
    )
      .via(Producer.flowWithContext(producerSettings))
  }

  def plainSource[T](inlet: CodecInlet[T], resetPosition: ResetPosition = Latest): Source[T, NotUsed] = {
    // TODO clean this up, lot of copying code, refactor.
    val savepointPath = findSavepointPathForPort(inlet)
    val topic = savepointPath.value
    val gId = groupId(savepointPath, streamletRef, inlet)
    val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(gId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, resetPosition.autoOffsetReset)

    Consumer.plainSource(consumerSettings, Subscriptions.topics(topic))
      .mapMaterializedValue(_ ⇒ NotUsed) // TODO we should likely use control to gracefully stop.
      .via(handleTermination)
      .map { record ⇒
        inlet.codec.decode(record.value)
      }
  }

  def plainSink[T](outlet: CodecOutlet[T]): Sink[T, NotUsed] = {
    val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
      .withBootstrapServers(bootstrapServers)
    val savepointPath = findSavepointPathForPort(outlet)
    val topic = savepointPath.value

    Flow[T]
      .map { value ⇒
        val key = outlet.partitioner(value)
        val bytesKey = keyBytes(key)
        val bytesValue = outlet.codec.encode(value)
        new ProducerRecord(topic, bytesKey, bytesValue)
      }
      .via(handleTermination)
      .to(Producer.plainSink(producerSettings))
      .mapMaterializedValue(_ ⇒ NotUsed)
  }

  def sinkRef[T](outlet: CodecOutlet[T]): WritableSinkRef[T] = {
    val savepointPath = findSavepointPathForPort(outlet)

    new KafkaSinkRef(
      system,
      outlet,
      bootstrapServers,
      savepointPath.value,
      killSwitch,
      completionPromise
    )
  }

  private def keyBytes(key: String) = if (key != null) key.getBytes("UTF8") else null

  private def findSavepointPathForPort(port: StreamletPort): SavepointPath = {
    streamletDefinition.resolveSavepoint(port)
      .getOrElse(throw PortNotFoundException(port, this))
  }

  private val stoppers = new AtomicReference(Vector.empty[() ⇒ Future[Dun]])

  def onStop(f: () ⇒ Future[Dun]): Unit = {
    stoppers.getAndUpdate(old ⇒ old :+ f)
  }

  private def handleTermination[T]: Flow[T, T, NotUsed] = {
    Flow[T]
      .via(killSwitch.flow)
      .alsoTo(
        Sink.onComplete {
          case Success(_) ⇒
            system.log.error(s"Stream has completed unexpectedly, shutting down streamlet.")
            completionPromise.success(Dun)
          case Failure(e) ⇒
            system.log.error(e, "Stream has failed, shutting down streamlet.")
            completionPromise.failure(e)
        }
      )
  }

  def signalReady(): Boolean = readyPromise.trySuccess(Dun)

  def stop(): Future[Dun] = {
    // we created this file when the pod started running (see AkkaStreamlet#run)
    Files.deleteIfExists(Paths.get(s"/tmp/$streamletRef.txt"))

    killSwitch.shutdown()
    import system.dispatcher
    Future.sequence(
      stoppers.get.map { f ⇒
        f().recover {
          case cause ⇒
            system.log.error(cause, "onStop callback failed.")
            Dun
        }
      }
    ).flatMap { _ ⇒
        completionPromise.trySuccess(Dun)
        completionFuture
      }
  }

  def metricTags(): Map[String, String] = {
    Map(
      "app-id" -> streamletDefinition.appId,
      "app-version" -> streamletDefinition.appVersion,
      "streamlet-ref" -> streamletRef
    )
  }
}
