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

import java.nio.charset.StandardCharsets

import scala.collection.immutable
import scala.concurrent._
import scala.util._
import akka._
import akka.actor.{ ActorSystem, CoordinatedShutdown }
import akka.cluster.sharding.external.ExternalShardAllocationStrategy
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }
import akka.kafka._
import akka.kafka.ConsumerMessage._
import akka.kafka.cluster.sharding.KafkaClusterSharding
import akka.kafka.scaladsl._
import akka.stream.scaladsl._
import cloudflow.akkastream.internal.{ HealthCheckFiles, StreamletExecutionImpl }
import com.typesafe.config._
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization._
import cloudflow.streamlets._

import scala.concurrent.duration.{ DurationInt, FiniteDuration }

/**
 * Implementation of the StreamletContext trait.
 */
final class AkkaStreamletContextImpl(
    private[cloudflow] override val streamletDefinition: StreamletDefinition,
    sys: ActorSystem
) extends AkkaStreamletContext {

  implicit val system: ActorSystem = sys

  override def config: Config = streamletDefinition.config

  private val execution                               = new StreamletExecutionImpl(this)
  override val streamletExecution: StreamletExecution = execution

  // internal implementation that uses the CommittableOffset implementation to provide access to the underlying offsets
  private[akkastream] def sourceWithContext[T](inlet: CodecInlet[T]): SourceWithContext[T, CommittableOffset, _] = {
    val topic = findTopicForPort(inlet)
    val gId   = topic.groupId(streamletDefinition.appId, streamletRef, inlet)

    val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(runtimeBootstrapServers(topic))
      .withGroupId(gId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperties(topic.kafkaConsumerProperties)

    system.log.info(s"Creating committable source for group: $gId topic: ${topic.name}")

    Consumer
      .sourceWithOffsetContext(consumerSettings, Subscriptions.topics(topic.name))
      // TODO clean this up, once SourceWithContext has mapError and mapMaterializedValue
      .asSource
      .mapMaterializedValue(_ => NotUsed) // TODO we should likely use control to gracefully stop.
      .via(handleTermination)
      .map {
        case (record, committableOffset) => inlet.codec.decode(record.value) -> committableOffset
      }
      .asSourceWithContext { case (_, committableOffset) => committableOffset }
      .map { case (record, _) => record }
  }

  override def sourceWithCommittableContext[T](inlet: CodecInlet[T]): cloudflow.akkastream.scaladsl.SourceWithCommittableContext[T] =
    sourceWithContext[T](inlet)

  private[akkastream] def shardedSourceWithContext[T, M, E](
      inlet: CodecInlet[T],
      shardEntity: Entity[M, E],
      kafkaTimeout: FiniteDuration = 10.seconds
  ): SourceWithContext[T, CommittableOffset, Future[NotUsed]] = {
    val topic = findTopicForPort(inlet)
    val gId   = topic.groupId(streamletDefinition.appId, streamletRef, inlet)

    val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(runtimeBootstrapServers(topic))
      .withGroupId(gId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperties(topic.kafkaConsumerProperties)

    val rebalanceListener: akka.actor.typed.ActorRef[ConsumerRebalanceEvent] =
      KafkaClusterSharding(system).rebalanceListener(shardEntity.typeKey)

    import akka.actor.typed.scaladsl.adapter._
    val subscription = Subscriptions
      .topics(topic.name)
      .withRebalanceListener(rebalanceListener.toClassic)

    system.log.info(s"Creating sharded committable source for group: $gId topic: ${topic.name}")

    val messageExtractor: Future[KafkaClusterSharding.KafkaShardingMessageExtractor[M]] =
      KafkaClusterSharding(system).messageExtractor(
        topic = topic.name,
        timeout = kafkaTimeout,
        settings = consumerSettings
      )

    Source
      .futureSource {
        messageExtractor.map { m =>
          ClusterSharding(system.toTyped).init(
            shardEntity
              .withAllocationStrategy(
                shardEntity.allocationStrategy
                  .getOrElse(new ExternalShardAllocationStrategy(system, shardEntity.typeKey.name))
              )
              .withMessageExtractor(m)
          )

          Consumer
            .sourceWithOffsetContext(consumerSettings, subscription)
            // TODO clean this up, once SourceWithContext has mapError and mapMaterializedValue
            .asSource
            .mapMaterializedValue(_ => NotUsed) // TODO we should likely use control to gracefully stop.
            .via(handleTermination)
            .map {
              case (record, committableOffset) => inlet.codec.decode(record.value) -> committableOffset
            }
        }(system.dispatcher)
      }
      .asSourceWithContext { case (_, committableOffset) => committableOffset }
      .map { case (record, _) => record }
  }

  override def shardedSourceWithCommittableContext[T, M, E](
      inlet: CodecInlet[T],
      shardEntity: Entity[M, E],
      kafkaTimeout: FiniteDuration = 10.seconds
  ): SourceWithContext[T, CommittableOffset, Future[NotUsed]] =
    shardedSourceWithContext(inlet, shardEntity)

  @deprecated("Use sourceWithCommittableContext", "1.3.4")
  override def sourceWithOffsetContext[T](inlet: CodecInlet[T]): cloudflow.akkastream.scaladsl.SourceWithOffsetContext[T] =
    sourceWithContext[T](inlet)

  def committableSink[T](outlet: CodecOutlet[T], committerSettings: CommitterSettings): Sink[(T, Committable), NotUsed] = {
    val topic = findTopicForPort(outlet)
    val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
      .withBootstrapServers(runtimeBootstrapServers(topic))
      .withProperties(topic.kafkaProducerProperties)

    Flow[(T, Committable)]
      .map {
        case (value, committable) =>
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

  override def flexiFlow[T](
      outlet: CodecOutlet[T]
  ): Flow[(immutable.Seq[_ <: T], Committable), (Unit, Committable), NotUsed] = {
    val topic = findTopicForPort(outlet)
    val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
      .withBootstrapServers(runtimeBootstrapServers(topic))
      .withProperties(topic.kafkaProducerProperties)

    Flow[(immutable.Seq[T], Committable)]
      .map {
        case (values, committable) =>
          ProducerMessage.MultiMessage(values.map(value => producerRecord(outlet, topic, value)), committable)
      }
      .via(handleTermination)
      .via(Producer.flexiFlow(producerSettings))
      .map(results => ((), results.passThrough))
  }

  private def producerRecord[T](outlet: CodecOutlet[T], topic: Topic, value: T) = {
    val key        = outlet.partitioner(value)
    val bytesKey   = keyBytes(key)
    val bytesValue = outlet.codec.encode(value)
    new ProducerRecord(topic.name, bytesKey, bytesValue)
  }

  private[akkastream] def sinkWithOffsetContext[T](outlet: CodecOutlet[T],
                                                   committerSettings: CommitterSettings): Sink[(T, CommittableOffset), NotUsed] = {
    val topic = findTopicForPort(outlet)
    val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
      .withBootstrapServers(runtimeBootstrapServers(topic))
      .withProperties(topic.kafkaProducerProperties)

    Flow[(T, CommittableOffset)]
      .map {
        case (value, committable) =>
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
      .withBootstrapServers(runtimeBootstrapServers(topic))
      .withGroupId(gId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, resetPosition.autoOffsetReset)
      .withProperties(topic.kafkaConsumerProperties)

    Consumer
      .plainSource(consumerSettings, Subscriptions.topics(topic.name))
      .mapMaterializedValue(_ => NotUsed) // TODO we should likely use control to gracefully stop.
      .via(handleTermination)
      .map { record =>
        inlet.codec.decode(record.value)
      }
  }

  def shardedPlainSource[T, M, E](inlet: CodecInlet[T],
                                  shardEntity: Entity[M, E],
                                  resetPosition: ResetPosition = Latest,
                                  kafkaTimeout: FiniteDuration = 10.seconds): Source[T, Future[NotUsed]] = {
    val topic = findTopicForPort(inlet)
    val gId   = topic.groupId(streamletDefinition.appId, streamletRef, inlet)
    val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(runtimeBootstrapServers(topic))
      .withGroupId(gId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, resetPosition.autoOffsetReset)
      .withProperties(topic.kafkaConsumerProperties)

    val rebalanceListener: akka.actor.typed.ActorRef[ConsumerRebalanceEvent] =
      KafkaClusterSharding(system).rebalanceListener(shardEntity.typeKey)

    import akka.actor.typed.scaladsl.adapter._
    val subscription = Subscriptions
      .topics(topic.name)
      .withRebalanceListener(rebalanceListener.toClassic)

    system.log.info(s"Creating sharded plain source for group: $gId topic: ${topic.name}")

    val messageExtractor: Future[KafkaClusterSharding.KafkaShardingMessageExtractor[M]] =
      KafkaClusterSharding(system).messageExtractor(
        topic = topic.name,
        timeout = kafkaTimeout,
        settings = consumerSettings
      )

    Source
      .futureSource {
        messageExtractor.map { m =>
          ClusterSharding(system.toTyped).init(
            shardEntity
              .withAllocationStrategy(
                shardEntity.allocationStrategy
                  .getOrElse(new ExternalShardAllocationStrategy(system, shardEntity.typeKey.name))
              )
              .withMessageExtractor(m)
          )

          Consumer
            .plainSource(consumerSettings, subscription)
            .mapMaterializedValue(_ => NotUsed) // TODO we should likely use control to gracefully stop.
            .via(handleTermination)
            .map { record =>
              inlet.codec.decode(record.value)
            }
        }(system.dispatcher)
      }
  }

  def plainSink[T](outlet: CodecOutlet[T]): Sink[T, NotUsed] = {
    val topic = findTopicForPort(outlet)
    val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
      .withBootstrapServers(runtimeBootstrapServers(topic))
      .withProperties(topic.kafkaProducerProperties)

    Flow[T]
      .map { value =>
        val key        = outlet.partitioner(value)
        val bytesKey   = keyBytes(key)
        val bytesValue = outlet.codec.encode(value)
        new ProducerRecord(topic.name, bytesKey, bytesValue)
      }
      .via(handleTermination)
      .to(Producer.plainSink(producerSettings))
      .mapMaterializedValue(_ => NotUsed)
  }

  def sinkRef[T](outlet: CodecOutlet[T]): WritableSinkRef[T] = {
    val topic = findTopicForPort(outlet)

    new KafkaSinkRef(
      system,
      outlet,
      runtimeBootstrapServers(topic),
      topic,
      killSwitch,
      execution.completionPromise
    )
  }

  private def keyBytes(key: String) = if (key != null) key.getBytes(StandardCharsets.UTF_8) else null

  private def handleTermination[T]: Flow[T, T, NotUsed] =
    Flow[T]
      .via(killSwitch.flow)
      .alsoTo(
        Sink.onComplete { res =>
          execution.complete(res)
          val streamletDefinitionMsg: String = s"${streamletDefinition.streamletRef} (${streamletDefinition.streamletClass})"
          res match {
            case Success(_) =>
              system.log.info(
                s"Stream has completed. Shutting down streamlet $streamletDefinitionMsg."
              )
            case Failure(e) =>
              system.log.error(e, s"Stream has failed. Shutting down streamlet $streamletDefinitionMsg.")
          }
        }
      )

  def signalReady(): Boolean = execution.signalReady()

  override def ready(localMode: Boolean): Unit = {
    // readiness probe to be done at operator using this
    // the streamlet context has been created and the streamlet is ready to take requests
    // needs to be done only in cluster mode - not in local running
    if (!localMode) HealthCheckFiles.createReady(streamletRef)
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, s"akka-streamlet-${streamletRef}-unbind") { () =>
      serviceUnbind()
    }
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeClusterShutdown, s"akka-streamlet-${streamletRef}-stop") { () =>
      stop().map(_ => Done)(system.dispatcher)
    }
  }

  override def alive(localMode: Boolean): Unit =
    // create a marker file indicating that the streamlet has started running
    // this will be used for pod liveness probe
    // needs to be done only in cluster mode - not in local running
    if (!localMode) HealthCheckFiles.createAlive(streamletRef)

  private def serviceUnbind(): Future[Done] =
    Future {
      HealthCheckFiles.deleteReady(streamletRef)
      Done
    }(system.dispatcher)

  override def stop(): Future[Dun] = {
    HealthCheckFiles.deleteReady(streamletRef)

    killSwitch.shutdown()
    import system.dispatcher
    Stoppers
      .stop()
      .flatMap(_ => execution.complete())
  }

  override def stopOnException(nonFatal: Throwable): Unit =
    stop()

  override def metricTags(): Map[String, String] =
    Map(
      "app-id"        -> streamletDefinition.appId,
      "app-version"   -> streamletDefinition.appVersion,
      "streamlet-ref" -> streamletRef
    )
}
