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

package cloudflow.akkastream

import java.util.concurrent.atomic.AtomicReference
import java.nio.file.{ Files, Paths }

import scala.concurrent._
import scala.util._
import akka._
import akka.actor.ActorSystem
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.kafka._
import akka.kafka.ConsumerMessage._
import akka.kafka.cluster.sharding.KafkaClusterSharding
import akka.kafka.scaladsl._
import akka.stream._
import akka.stream.scaladsl._
import com.typesafe.config._
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization._
import cloudflow.streamlets._

/**
 * Implementation of the StreamletContext trait.
 */
final class AkkaStreamletContextImpl(
    private[cloudflow] override val streamletDefinition: StreamletDefinition,
    sys: ActorSystem
) extends AkkaStreamletContext {
  implicit val system: ActorSystem  = sys
  implicit val ec: ExecutionContext = sys.dispatcher

  override def config: Config = streamletDefinition.config

  private val readyPromise      = Promise[Dun]()
  private val completionPromise = Promise[Dun]()
  private val completionFuture  = completionPromise.future

  val killSwitch = KillSwitches.shared(streamletRef)

  val streamletExecution = new StreamletExecution() {
    val readyFuture            = readyPromise.future
    def completed: Future[Dun] = completionFuture
    def ready: Future[Dun]     = readyFuture
    def stop(): Future[Dun]    = AkkaStreamletContextImpl.this.stop()
  }

  // internal implementation that uses the CommittableOffset implementation to provide access to the underlying offsets
  private[akkastream] def sourceWithContext[T](inlet: CodecInlet[T]): SourceWithContext[T, CommittableOffset, _] = {
    val topic = findTopicForPort(inlet)
    val gId   = topic.groupId(streamletDefinition.appId, streamletRef, inlet)

    val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(topic.bootstrapServers.getOrElse(internalKafkaBootstrapServers))
      .withGroupId(gId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperties(topic.kafkaConsumerProperties)

    system.log.info(s"Creating committable source for group: $gId topic: ${topic.name}")

    Consumer
      .sourceWithOffsetContext(consumerSettings, Subscriptions.topics(topic.name))
      // TODO clean this up, once SourceWithContext has mapError and mapMaterializedValue
      .asSource
      .mapMaterializedValue(_ ⇒ NotUsed) // TODO we should likely use control to gracefully stop.
      .via(handleTermination)
      .map {
        case (record, committableOffset) ⇒ inlet.codec.decode(record.value) -> committableOffset
      }
      .asSourceWithContext { case (_, committableOffset) ⇒ committableOffset }
      .map { case (record, _) ⇒ record }
  }

  override def sourceWithCommittableContext[T](inlet: CodecInlet[T]): cloudflow.akkastream.scaladsl.SourceWithCommittableContext[T] =
    sourceWithContext[T](inlet)

  private[akkastream] def shardedSourceWithContext[T, E](inlet: CodecInlet[T],
                                                         typeKey: EntityTypeKey[E],
                                                         entityIdExtractor: E => String): Source[ShardedSourceEnvelope[T, E], _] = {
    val topic = findTopicForPort(inlet)
    val gId   = topic.groupId(streamletDefinition.appId, streamletRef, inlet)

    val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(topic.bootstrapServers.getOrElse(internalKafkaBootstrapServers))
      .withGroupId(gId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperties(topic.kafkaConsumerProperties)

    val rebalanceListener: akka.actor.typed.ActorRef[ConsumerRebalanceEvent] =
      KafkaClusterSharding(system).rebalanceListener(typeKey)

    import akka.actor.typed.scaladsl.adapter._
    val subscription = Subscriptions
      .topics(topic.name)
      .withRebalanceListener(rebalanceListener.toClassic)

    system.log.info(s"Creating committable source for group: $gId topic: ${topic.name}")

    val consumer = Consumer
      .sourceWithOffsetContext(consumerSettings, Subscriptions.topics(topic.name))
      // TODO clean this up, once SourceWithContext has mapError and mapMaterializedValue
      .asSource
      .mapMaterializedValue(_ ⇒ NotUsed) // TODO we should likely use control to gracefully stop.
      .via(handleTermination)
      .map {
        case (record, committableOffset) ⇒ inlet.codec.decode(record.value) -> committableOffset
      }
      .asSourceWithContext { case (_, committableOffset) ⇒ committableOffset }
      .map { case (record, _) ⇒ record }

    import scala.concurrent.duration._
    val messageExtractor: Future[KafkaClusterSharding.KafkaShardingNoEnvelopeExtractor[E]] =
      KafkaClusterSharding(system).messageExtractorNoEnvelope(
        timeout = 10.seconds,
        topic = "user-topic",
        entityIdExtractor = entityIdExtractor,
        settings = consumerSettings
      )

    Source.future {
      messageExtractor.map { messageExtractor =>
        ShardedSourceEnvelope(consumer, messageExtractor)
      }
    }
  }

  override def shardedSourceWithCommittableContext[T, E](
      inlet: CodecInlet[T],
      typeKey: EntityTypeKey[E],
      entityIdExtractor: E => String
  ): Source[ShardedSourceEnvelope[T, E], _] =
    shardedSourceWithContext[T, E](inlet, typeKey, entityIdExtractor)

  @deprecated("Use sourceWithCommittableContext", "1.3.4")
  override def sourceWithOffsetContext[T](inlet: CodecInlet[T]): cloudflow.akkastream.scaladsl.SourceWithOffsetContext[T] =
    sourceWithContext[T](inlet)

  def committableSink[T](outlet: CodecOutlet[T], committerSettings: CommitterSettings): Sink[(T, Committable), NotUsed] = {
    val topic = findTopicForPort(outlet)
    val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
      .withBootstrapServers(topic.bootstrapServers.getOrElse(internalKafkaBootstrapServers))
      .withProperties(topic.kafkaProducerProperties)

    Flow[(T, Committable)]
      .map {
        case (value, committable) ⇒
          val key        = outlet.partitioner(value)
          val bytesKey   = keyBytes(key)
          val bytesValue = outlet.codec.encode(value)
          ProducerMessage.Message(new ProducerRecord(topic.name, bytesKey, bytesValue), committable)
      }
      .via(handleTermination)
      .toMat(Producer.committableSink(producerSettings, committerSettings))(Keep.left)
  }

  def committableSink[T](committerSettings: CommitterSettings): Sink[(T, Committable), NotUsed] =
    Flow[(T, Committable)].toMat(Committer.sinkWithOffsetContext(committerSettings))(Keep.left)

  private[akkastream] def sinkWithOffsetContext[T](outlet: CodecOutlet[T],
                                                   committerSettings: CommitterSettings): Sink[(T, CommittableOffset), NotUsed] = {
    val topic = findTopicForPort(outlet)
    val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
      .withBootstrapServers(topic.bootstrapServers.getOrElse(internalKafkaBootstrapServers))
      .withProperties(topic.kafkaProducerProperties)

    Flow[(T, CommittableOffset)]
      .map {
        case (value, committable) ⇒
          val key        = outlet.partitioner(value)
          val bytesKey   = keyBytes(key)
          val bytesValue = outlet.codec.encode(value)
          ProducerMessage.Message(new ProducerRecord(topic.name, bytesKey, bytesValue), committable)
      }
      .toMat(Producer.committableSink(producerSettings, committerSettings))(Keep.left)
  }

  private[akkastream] def sinkWithOffsetContext[T](committerSettings: CommitterSettings): Sink[(T, CommittableOffset), NotUsed] =
    Flow[(T, CommittableOffset)].toMat(Committer.sinkWithOffsetContext(committerSettings))(Keep.left)

  def plainSource[T](inlet: CodecInlet[T], resetPosition: ResetPosition = Latest): Source[T, NotUsed] = {
    // TODO clean this up, lot of copying code, refactor.
    val topic = findTopicForPort(inlet)
    val gId   = topic.groupId(streamletDefinition.appId, streamletRef, inlet)
    val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(topic.bootstrapServers.getOrElse(internalKafkaBootstrapServers))
      .withGroupId(gId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, resetPosition.autoOffsetReset)
      .withProperties(topic.kafkaConsumerProperties)

    Consumer
      .plainSource(consumerSettings, Subscriptions.topics(topic.name))
      .mapMaterializedValue(_ ⇒ NotUsed) // TODO we should likely use control to gracefully stop.
      .via(handleTermination)
      .map { record ⇒
        inlet.codec.decode(record.value)
      }
  }

  def plainSink[T](outlet: CodecOutlet[T]): Sink[T, NotUsed] = {
    val topic = findTopicForPort(outlet)
    val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
      .withBootstrapServers(topic.bootstrapServers.getOrElse(internalKafkaBootstrapServers))
      .withProperties(topic.kafkaProducerProperties)

    Flow[T]
      .map { value ⇒
        val key        = outlet.partitioner(value)
        val bytesKey   = keyBytes(key)
        val bytesValue = outlet.codec.encode(value)
        new ProducerRecord(topic.name, bytesKey, bytesValue)
      }
      .via(handleTermination)
      .to(Producer.plainSink(producerSettings))
      .mapMaterializedValue(_ ⇒ NotUsed)
  }

  def shardedPlainSource[T, E](
      inlet: CodecInlet[T],
      typeKey: EntityTypeKey[E],
      entityIdExtractor: E => String,
      resetPosition: ResetPosition = Latest
  ): (Source[T, NotUsed], Future[KafkaClusterSharding.KafkaShardingNoEnvelopeExtractor[E]]) = {
    // TODO clean this up, lot of copying code, refactor.
    val topic = findTopicForPort(inlet)
    val gId   = topic.groupId(streamletDefinition.appId, streamletRef, inlet)
    val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(topic.bootstrapServers.getOrElse(internalKafkaBootstrapServers))
      .withGroupId(gId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, resetPosition.autoOffsetReset)
      .withProperties(topic.kafkaConsumerProperties)

    val rebalanceListener: akka.actor.typed.ActorRef[ConsumerRebalanceEvent] =
      KafkaClusterSharding(system).rebalanceListener(typeKey)

    import akka.actor.typed.scaladsl.adapter._
    val subscription = Subscriptions
      .topics(topic.name)
      .withRebalanceListener(rebalanceListener.toClassic)

    val consumer = Consumer
      .plainSource(consumerSettings, subscription)
      .mapMaterializedValue(_ ⇒ NotUsed) // TODO we should likely use control to gracefully stop.
      .via(handleTermination)
      .map { record ⇒
        inlet.codec.decode(record.value)
      }

    import scala.concurrent.duration._
    val messageExtractor: Future[KafkaClusterSharding.KafkaShardingNoEnvelopeExtractor[E]] =
      KafkaClusterSharding(system).messageExtractorNoEnvelope(
        timeout = 10.seconds,
        topic = "user-topic",
        entityIdExtractor = entityIdExtractor,
        settings = consumerSettings
      )

    (consumer, messageExtractor)
  }

  def sinkRef[T](outlet: CodecOutlet[T]): WritableSinkRef[T] = {
    val topic = findTopicForPort(outlet)

    new KafkaSinkRef(
      system,
      outlet,
      internalKafkaBootstrapServers,
      topic,
      killSwitch,
      completionPromise
    )
  }

  private def keyBytes(key: String) = if (key != null) key.getBytes("UTF8") else null

  private val stoppers = new AtomicReference(Vector.empty[() ⇒ Future[Dun]])

  def onStop(f: () ⇒ Future[Dun]): Unit =
    stoppers.getAndUpdate(old ⇒ old :+ f)

  private def streamletDefinitionMsg: String = s"${streamletDefinition.streamletRef} (${streamletDefinition.streamletClass})"

  private def handleTermination[T]: Flow[T, T, NotUsed] =
    Flow[T]
      .via(killSwitch.flow)
      .alsoTo(
        Sink.onComplete {
          case Success(_) ⇒
            system.log.error(
              s"Stream has completed. Shutting down streamlet $streamletDefinitionMsg."
            )
            completionPromise.trySuccess(Dun)
          case Failure(e) ⇒
            system.log.error(e, s"Stream has failed. Shutting down streamlet $streamletDefinitionMsg.")
            completionPromise.tryFailure(e)
        }
      )

  def signalReady(): Boolean = readyPromise.trySuccess(Dun)

  def stop(): Future[Dun] = {
    // we created this file when the pod started running (see AkkaStreamlet#run)
    Files.deleteIfExists(Paths.get(s"/tmp/$streamletRef.txt"))

    killSwitch.shutdown()
    import system.dispatcher
    Future
      .sequence(
        stoppers.get.map { f ⇒
          f().recover {
            case cause ⇒
              system.log.error(cause, "onStop callback failed.")
              Dun
          }
        }
      )
      .flatMap { _ ⇒
        completionPromise.trySuccess(Dun)
        completionFuture
      }
  }

  def metricTags(): Map[String, String] =
    Map(
      "app-id"        -> streamletDefinition.appId,
      "app-version"   -> streamletDefinition.appVersion,
      "streamlet-ref" -> streamletRef
    )
}
