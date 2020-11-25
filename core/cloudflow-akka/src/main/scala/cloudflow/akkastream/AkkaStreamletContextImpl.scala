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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable
import scala.concurrent._
import scala.util._
import akka._
import akka.actor.{ ActorSystem, CoordinatedShutdown }
import akka.annotation.InternalApi
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
import org.slf4j.LoggerFactory

import scala.concurrent.duration.{ DurationInt, FiniteDuration }

/**
 * Implementation of the StreamletContext trait.
 */
@InternalApi
final class AkkaStreamletContextImpl(
    private[cloudflow] override val streamletDefinition: StreamletDefinition,
    sys: ActorSystem
) extends AkkaStreamletContext {
  private val log                            = LoggerFactory.getLogger(classOf[AkkaStreamletContextImpl])
  private val streamletDefinitionMsg: String = s"${streamletDefinition.streamletRef} (${streamletDefinition.streamletClass})"

  implicit val system: ActorSystem = sys

  override def config: Config = streamletDefinition.config

  private val StopTimeoutSetting = "cloudflow.akka.consumer-stop-timeout"
  private val consumerStopTimeout: FiniteDuration =
    FiniteDuration(sys.settings.config.getDuration(StopTimeoutSetting).toMillis, TimeUnit.MILLISECONDS).toCoarsest

  private val execution                               = new StreamletExecutionImpl(this)
  override val streamletExecution: StreamletExecution = execution

  /**
   * See https://doc.akka.io/docs/alpakka-kafka/current/consumer.html#controlled-shutdown
   */
  @InternalApi
  object KafkaControls {
    import akka.kafka.scaladsl.Consumer.Control
    private val controls = new AtomicReference(Set[Control]())

    def add(c: Control): Control = {
      controls.updateAndGet(set => set + c)
      c
    }

    def get: Set[Control] = controls.get()

    /**
     * Stop producing messages from all inlets and complete the streams.
     *
     * The underlying Kafka consumer stays alive so that it can handle commits for the
     * already enqueued messages. It does not unsubscribe from any topics/partitions
     * as that could trigger a consumer group rebalance.
     */
    def stopInflow()(implicit ec: ExecutionContext) = {
      log.debug("Stopping inflow from {}", streamletDefinitionMsg)
      Future
        .sequence(controls.get.map(_.stop().recover {
          case cause =>
            log.error("stopping the consumer source failed.", cause)
            Done
        }))
        .map(_ => Done)
    }

    /**
     * Shut down the consumer `Source`.
     *
     * After this no more commits from enqueued messages can be handled.
     * The actor will wait for acknowledgements of the already sent offset commits from the Kafka broker before shutting down.
     */
    def shutdownConsumers()(implicit ec: ExecutionContext) = {
      log.debug("Shutting down consumers of {}", streamletDefinitionMsg)
      Future
        .sequence(
          controls.get.map(_.shutdown().recover {
            case cause =>
              log.error("shutting down the consumer source failed.", cause)
              Done
          })
        )
        .map(_ => Done)
    }
  }

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
      .mapMaterializedValue { c =>
        KafkaControls.add(c)
        NotUsed
      }
      .map { record =>
        inlet.handleErrors(record.value, inlet.codec.decode(record.value)) match {
          case Some(value) => Some(value)
          case _           => None
        }
      }
      .collect { case Some(v) => v }
      .via(handleTermination)
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
            .mapMaterializedValue { c =>
              KafkaControls.add(c)
              NotUsed
            }
            .map { record =>
              inlet.handleErrors(record.value, inlet.codec.decode(record.value)) match {
                case Some(value) => Some(value)
                case _           => None
              }
            }
            .collect { case Some(v) => v }
            .via(handleTermination)
            .asSource
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
      .mapMaterializedValue { c =>
        KafkaControls.add(c)
        NotUsed
      }
      .via(handleTermination)
      .map { record =>
        inlet.handleErrors(record.value, inlet.codec.decode(record.value)) match {
          case Some(value) => Some(value)
          case _           => None
        }
      }
      .collect { case Some(v) => v }
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
            .mapMaterializedValue { c =>
              KafkaControls.add(c)
              NotUsed
            }
            .via(handleTermination)
            .map { record =>
              inlet.handleErrors(record.value, inlet.codec.decode(record.value)) match {
                case Some(value) => Some(value)
                case _           => None
              }
            }
            .collect { case Some(v) => v }
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
          res match {
            case Success(_) =>
              log.info("Stream has completed. Shutting down streamlet {}.", streamletDefinitionMsg)
            case Failure(e) =>
              log.error(s"Stream has failed. Shutting down streamlet $streamletDefinitionMsg.", e)
          }
        }
      )

  def signalReady(): Boolean = execution.signalReady()

  override def ready(localMode: Boolean): Unit = {
    // readiness probe to be done at operator using this
    // the streamlet context has been created and the streamlet is ready to take requests
    // needs to be done only in cluster mode - not in local running
    if (!localMode) HealthCheckFiles.createReady(streamletRef)

    import system.dispatcher
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, s"akka-streamlet-${streamletRef}-unbind") { () =>
      serviceUnbind()
    }
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeClusterShutdown, s"akka-streamlet-${streamletRef}-stop") { () =>
      stop().map(_ => Done)
    }
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseActorSystemTerminate, s"akka-streamlet-${streamletRef}-terminate") { () =>
      Future {
        HealthCheckFiles.deleteAlive(streamletRef)
        Done
      }
    }
  }

  override def alive(localMode: Boolean): Unit =
    // create a marker file indicating that the streamlet has started running
    // this will be used for pod liveness probe
    // needs to be done only in cluster mode - not in local running
    if (!localMode) HealthCheckFiles.createAlive(streamletRef)

  private def serviceUnbind(): Future[Done] = {
    HealthCheckFiles.deleteReady(streamletRef)
    KafkaControls.stopInflow()(system.dispatcher)
  }

  override def stop(): Future[Dun] = {
    HealthCheckFiles.deleteReady(streamletRef)

    import system.dispatcher
    KafkaControls
      .stopInflow()
      .flatMap { _ =>
        log.debug(s"Waiting {} ($StopTimeoutSetting) until {} consumers are shut down",
                  consumerStopTimeout: Any,
                  streamletDefinitionMsg: Any)
        akka.pattern.after(consumerStopTimeout)(Future.successful(Done))
      }
      .flatMap { _ =>
        KafkaControls.shutdownConsumers()
      }
      .map { _ =>
        // The kill switch wouldn't do anything in most cases
        // as `stopInflow` completes the sources and the stream should be completed by now
        log.debug("Triggering kill switch of {}", streamletDefinitionMsg)
        killSwitch.shutdown()
      }
      .flatMap(_ => Stoppers.stop())
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
