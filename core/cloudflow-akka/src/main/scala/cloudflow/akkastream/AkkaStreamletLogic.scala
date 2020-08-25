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

import java.nio.file.Path

import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.annotation.ApiMayChange
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }
import akka.stream.scaladsl._
import akka.kafka._
import akka.kafka.ConsumerMessage._
import com.typesafe.config.Config
import cloudflow.streamlets._
import cloudflow.akkastream.scaladsl._

import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, DurationInt, FiniteDuration }

/**
 * Provides an entry-point for defining the behavior of an AkkaStreamlet.
 * Override the `run` method to implement the specific logic / code that should be executed once the streamlet deployed
 * as part of a running cloudflow application.
 * See `RunnableGraphStreamletLogic` if you just want to create a RunnableGraph.
 */
abstract class AkkaStreamletLogic(implicit val context: AkkaStreamletContext) extends StreamletLogic[AkkaStreamletContext] {

  override def getContext(): AkkaStreamletContext = super.getContext()

  /**
   * This method is called when the streamlet is run.
   * Override this method to define what the specific streamlet logic should do.
   */
  def run(): Unit

  /**
   * Launch the execution of the graph.
   */
  final def runGraph[T](graph: RunnableGraph[T]): T = graph.run()

  /**
   * Java API
   * Launch the execution of the graph.
   */
  final def runGraph[T](graph: akka.stream.javadsl.RunnableGraph[T]): T = graph.run(system)

  /**
   * Signals that the streamlet is ready to process data.
   * `signalReady` completes the [[cloudflow.streamlets.StreamletExecution#ready]] future. When a streamlet is run using the testkit, a [[cloudflow.streamlets.StreamletExecution]] is returned.
   * [[cloudflow.streamlets.StreamletExecution#ready]] can be used for instance to wait
   * for a server streamlet to signal that it is ready to accept requests.
   */
  final def signalReady() = context.signalReady()

  /**
   * The ActorSystem that will run the Akkastreamlet.
   */
  implicit final val system: ActorSystem = context.system

  /**
   * Java API
   */
  def getSystem() = system

  /**
   * The default ExecutionContext of the ActorSystem (the system dispatcher).
   */
  implicit final val executionContext = system.dispatcher

  /**
   * Java API
   */
  def getExecutionContext() = executionContext

  /**
   * Helper method to make it easier to start typed cluster sharding
   * with an classic actor system
   */
  def clusterSharding() = ClusterSharding(system.toTyped)

  /**
   * This source emits `T` records together with the offset position as context, thus makes it possible
   * to commit offset positions to Kafka (as received through the `inlet`).
   * This is useful when "at-least once delivery" is desired, as each message will likely be
   * delivered one time, but in failure cases, they can be duplicated.
   *
   * It is intended to be used with `sinkWithOffsetContext(outlet: CodecOutlet[T])` or [[akka.kafka.scaladsl.Committer#sinkWithOffsetContext]],
   * which both commit the offset positions that accompany the records, read from this source.
   * `sinkWithOffsetContext(outlet: CodecOutlet[T])` should be used if you want to commit the offset positions after records have been written to the specified `outlet`.
   * The `inlet` specifies a [[cloudflow.streamlets.Codec]] that will be used to deserialize the records read from Kafka.
   */
  @deprecated("Use sourceWithCommittableContext", "1.3.4")
  def sourceWithOffsetContext[T](inlet: CodecInlet[T]): SourceWithOffsetContext[T] = context.sourceWithOffsetContext(inlet)

  /**
   * This source emits `T` records together with the committable context, thus makes it possible
   * to commit offset positions to Kafka (as received through the `inlet`).
   * This is useful when "at-least once delivery" is desired, as each message will likely be
   * delivered one time, but in failure cases, they can be duplicated.
   *
   * It is intended to be used with `committableSink(outlet: CodecOutlet[T])`,
   * which commits the offset positions that accompany the records that are read from this source
   * after the records have been written to the specified `outlet`.
   *
   * The `inlet` specifies a [[cloudflow.streamlets.Codec]] that is used to deserialize the records read from the underlying transport.
   */
  def sourceWithCommittableContext[T](inlet: CodecInlet[T]): SourceWithCommittableContext[T] =
    context.sourceWithCommittableContext(inlet)

  /**
   * Java API
   */
  @deprecated("Use getSourceWithCommittableContext", "1.3.4")
  def getSourceWithOffsetContext[T](inlet: CodecInlet[T]): akka.stream.javadsl.SourceWithContext[T, CommittableOffset, _] =
    sourceWithOffsetContext(inlet).asJava

  /**
   * Java API
   * @see [[sourceWithCommittableContext]]
   */
  def getSourceWithCommittableContext[T](inlet: CodecInlet[T]): akka.stream.javadsl.SourceWithContext[T, Committable, _] =
    context.sourceWithCommittableContext(inlet).asJava

  /**
   * This source is designed to function the same as [[sourceWithCommittableContext]]
   * while also leveraging Akka Kafka Cluster Sharding for stateful streaming.
   *
   * This source emits `T` records together with the committable context, thus makes it possible
   * to commit offset positions to Kafka using `committableSink(outlet: CodecOutlet[T])`.
   *
   * It is required to use this source with Akka Cluster.  This source will start up
   * Akka Cluster Sharding using the supplied `shardEntity` and configure the kafka external
   * shard strategy to co-locate Kafka partition consumption with Akka Cluster shards.
   *
   * @param inlet the inlet to consume messages from. The inlet specifies a [[cloudflow.streamlets.Codec]] that is used to deserialize the records read from the underlying transport.
   * @param shardEntity is used to specify the settings for the started shard region
   * @param kafkaTimeout is used to specify the amount of time the message extractor will wait for a response from kafka
   **/
  @ApiMayChange
  def shardedSourceWithCommittableContext[T, M, E](
      inlet: CodecInlet[T],
      shardEntity: Entity[M, E],
      kafkaTimeout: FiniteDuration = 10.seconds
  ): SourceWithContext[T, CommittableOffset, Future[NotUsed]] =
    context.shardedSourceWithCommittableContext(inlet, shardEntity, kafkaTimeout)

  /**
   * Java API
   * @see [[shardedSourceWithCommittableContext]]
   */
  @ApiMayChange
  def getShardedSourceWithCommittableContext[T, M, E](
      inlet: CodecInlet[T],
      shardEntity: Entity[M, E],
      kafkaTimeout: FiniteDuration = 10.seconds
  ): akka.stream.javadsl.SourceWithContext[T, Committable, Future[NotUsed]] =
    context.shardedSourceWithCommittableContext(inlet, shardEntity, kafkaTimeout).asJava

  /**
   * The `plainSource` emits `T` records (as received through the `inlet`).
   *
   * It has no support for committing offsets to Kafka.
   * The `inlet` specifies a [[cloudflow.streamlets.Codec]] that will be used to deserialize the records read from Kafka.
   */
  def plainSource[T](inlet: CodecInlet[T], resetPosition: ResetPosition = Latest): akka.stream.scaladsl.Source[T, NotUsed] =
    context.plainSource(inlet, resetPosition)

  /**
   * Java API
   */
  def getPlainSource[T](inlet: CodecInlet[T]): akka.stream.javadsl.Source[T, NotUsed] = plainSource(inlet).asJava

  /**
   * Java API
   */
  def getPlainSource[T](inlet: CodecInlet[T], resetPosition: ResetPosition): akka.stream.javadsl.Source[T, NotUsed] =
    plainSource(inlet, resetPosition).asJava

  /**
   * This source is designed to function the same as [[plainSource]]
   * while also leveraging Akka Kafka Cluster Sharding for stateful streaming.
   *
   * The `plainSource` emits `T` records (as received through the `inlet`).
   *
   * It has no support for committing offsets to Kafka.
   *
   * It is required to use this source with Akka Cluster.  This source will start up
   * Akka Cluster Sharding using the supplied `shardEntity` and configure the kafka external
   * shard strategy to co-locate Kafka partition consumption with Akka Cluster shards.
   *
   * @param inlet the inlet to consume messages from. The inlet specifies a [[cloudflow.streamlets.Codec]] that is used to deserialize the records read from the underlying transport.
   * @param shardEntity is used to specific the settings for the started shard region
   * @param kafkaTimeout is used to specify the amount of time the message extractor will wait for a response from kafka
   **/
  @ApiMayChange
  def shardedPlainSource[T, M, E](inlet: CodecInlet[T],
                                  shardEntity: Entity[M, E],
                                  resetPosition: ResetPosition = Latest,
                                  kafkaTimeout: FiniteDuration = 10.seconds): Source[T, Future[NotUsed]] =
    context.shardedPlainSource(inlet, shardEntity, resetPosition, kafkaTimeout)

  /**
   * Java API
   */
  @ApiMayChange
  def getShardedPlainSource[T, M, E](inlet: CodecInlet[T],
                                     shardEntity: Entity[M, E],
                                     kafkaTimeout: FiniteDuration): akka.stream.javadsl.Source[T, Future[NotUsed]] =
    shardedPlainSource(inlet, shardEntity, Latest, kafkaTimeout).asJava

  /**
   * Java API
   */
  @ApiMayChange
  def getShardedPlainSource[T, M, E](inlet: CodecInlet[T],
                                     shardEntity: Entity[M, E],
                                     resetPosition: ResetPosition = Latest,
                                     kafkaTimeout: FiniteDuration = 10.seconds): akka.stream.javadsl.Source[T, Future[NotUsed]] =
    shardedPlainSource(inlet, shardEntity, resetPosition, kafkaTimeout).asJava

  /**
   * Creates a sink for publishing `T` records to the outlet. The records are partitioned according to the `partitioner` of the `outlet`.
   * The `outlet` specifies a [[cloudflow.streamlets.Codec]] that will be used to serialize the records that are written to Kafka.
   */
  def plainSink[T](outlet: CodecOutlet[T]): Sink[T, NotUsed] = context.plainSink(outlet)

  /**
   * Java API
   */
  def getPlainSink[T](outlet: CodecOutlet[T]): akka.stream.javadsl.Sink[T, NotUsed] = plainSink(outlet).asJava

  /**
   * The [[akka.kafka.CommitterSettings]] that have been configured
   * from the default configuration
   * `akka.kafka.committer`.
   */
  val defaultCommitterSettings = CommitterSettings(system)

  /**
   * Java API
   */
  def getDefaultCommitterSettings() = defaultCommitterSettings

  /**
   * Creates a sink for publishing records to the outlet. The records are partitioned according to the `partitioner` of the `outlet`.
   * Batches offsets from the contexts that accompany the records, and commits these to Kafka.
   * The `outlet` specifies a [[cloudflow.streamlets.Codec]] that will be used to serialize the records that are written to Kafka.
   */
  def committableSink[T](outlet: CodecOutlet[T],
                         committerSettings: CommitterSettings = defaultCommitterSettings): Sink[(T, Committable), NotUsed] =
    context.committableSink(outlet, committerSettings)

  /**
   * Creates a sink, purely for committing the offsets that have been read further upstream.
   * Batches offsets from the contexts that accompany the records, and commits these to Kafka.
   */
  def committableSink[T](committerSettings: CommitterSettings): Sink[(T, Committable), NotUsed] =
    context.committableSink(committerSettings)

  /**
   * Creates a sink, purely for committing the offsets that have been read further upstream.
   * Batches offsets from the contexts that accompany the records, and commits these to Kafka.
   * Uses a default CommitterSettings, which is configured
   * through the default configuration in `akka.kafka.committer`.
   */
  def committableSink[T]: Sink[(T, Committable), NotUsed] =
    committableSink[T](defaultCommitterSettings)

  /**
   * Creates a sink for publishing records to the outlet. The records are partitioned according to the `partitioner` of the `outlet`.
   * Batches offsets from the contexts that accompany the records, and commits these to Kafka.
   * The `outlet` specifies a [[cloudflow.streamlets.Codec]] that will be used to serialize the records that are written to Kafka.
   */
  @deprecated("Use `committableSink` instead.", "1.3.1")
  def sinkWithOffsetContext[T](outlet: CodecOutlet[T],
                               committerSettings: CommitterSettings = defaultCommitterSettings): Sink[(T, CommittableOffset), NotUsed] =
    context.sinkWithOffsetContext(outlet, committerSettings)

  /**
   * Creates a sink, purely for committing the offsets that have been read further upstream.
   * Batches offsets from the contexts that accompany the records, and commits these to Kafka.
   */
  @deprecated("Use `committableSink` instead.", "1.3.1")
  def sinkWithOffsetContext[T](committerSettings: CommitterSettings): Sink[(T, CommittableOffset), NotUsed] =
    context.sinkWithOffsetContext(committerSettings).mapMaterializedValue(_ ⇒ NotUsed)

  /**
   * Creates a sink, purely for committing the offsets that have been read further upstream.
   * Batches offsets from the contexts that accompany the records, and commits these to Kafka.
   */
  @deprecated("Use `committableSink` instead.", "1.3.1")
  def sinkWithOffsetContext[T]: Sink[(T, CommittableOffset), NotUsed] =
    sinkWithOffsetContext(defaultCommitterSettings)

  /**
   * Java API
   */
  def getCommittableSink[T](outlet: CodecOutlet[T],
                            committerSettings: CommitterSettings): akka.stream.javadsl.Sink[akka.japi.Pair[T, Committable], NotUsed] =
    committableSink[T](outlet, committerSettings).asJava.contramap { case pair ⇒ (pair.first, pair.second) }

  /**
   * Java API
   */
  def getCommittableSink[T](outlet: CodecOutlet[T]): akka.stream.javadsl.Sink[akka.japi.Pair[T, Committable], NotUsed] =
    getCommittableSink[T](outlet, defaultCommitterSettings)

  /**
   * Java API
   */
  def getCommittableSink[T](committerSettings: CommitterSettings): akka.stream.javadsl.Sink[akka.japi.Pair[T, Committable], NotUsed] =
    committableSink[T](committerSettings).asJava.contramap { case pair ⇒ (pair.first, pair.second) }

  /**
   * Java API
   */
  def getCommittableSink[T](): akka.stream.javadsl.Sink[akka.japi.Pair[T, Committable], NotUsed] =
    getCommittableSink[T](defaultCommitterSettings)

  /**
   * Java API
   */
  @deprecated("Use `getCommittableSink` instead.", "1.3.1")
  def getSinkWithOffsetContext[T](outlet: CodecOutlet[T]): akka.stream.javadsl.Sink[akka.japi.Pair[T, CommittableOffset], NotUsed] =
    getSinkWithOffsetContext(outlet, defaultCommitterSettings)

  /**
   * Java API
   */
  @deprecated("Use `getCommittableSink` instead.", "1.3.1")
  def getSinkWithOffsetContext[T](
      outlet: CodecOutlet[T],
      committerSettings: CommitterSettings
  ): akka.stream.javadsl.Sink[akka.japi.Pair[T, CommittableOffset], NotUsed] =
    committableSink[T](outlet, committerSettings).asJava.contramap { case pair ⇒ (pair.first, pair.second) }

  /**
   * Java API
   */
  @deprecated("Use `getCommittableSink` instead.", "1.3.1")
  def getSinkWithOffsetContext[T](
      committerSettings: CommitterSettings
  ): akka.stream.javadsl.Sink[akka.japi.Pair[T, CommittableOffset], NotUsed] =
    committableSink[T](committerSettings).asJava.contramap { case pair ⇒ (pair.first, pair.second) }

  /**
   * Java API
   */
  @deprecated("Use `getCommittableSink` instead.", "1.3.1")
  def getSinkWithOffsetContext[T](): akka.stream.javadsl.Sink[akka.japi.Pair[T, CommittableOffset], NotUsed] =
    getSinkWithOffsetContext(defaultCommitterSettings)

  /**
   * Creates a [[akka.stream.SinkRef SinkRef]] to write to, for the specified [[cloudflow.streamlets.CodecOutlet CodeOutlet]].
   * The records are partitioned according to the `partitioner` of the `outlet`.
   *
   * @param outlet the specified [[cloudflow.streamlets.CodecOutlet CodeOutlet]]
   * @return the [[cloudflow.akkastream.WritableSinkRef WritebleSinkRef]] created
   */
  final def sinkRef[T](outlet: CodecOutlet[T]): WritableSinkRef[T] = context.sinkRef(outlet)

  /**
   * Java API
   */
  final def getSinkRef[T](outlet: CodecOutlet[T]): WritableSinkRef[T] = sinkRef[T](outlet)

  /**
   * The full configuration for the [[AkkaStreamlet]], containing all
   * deployment-time configuration parameters on top of the normal
   * configuration as loaded through ActorSystem.settings.config
   */
  final def config: Config = context.config

  /**
   * Java API
   */
  final def getConfig(): Config = config

  /**
   * The subset of configuration specific to a single named instance of a streamlet.
   *
   * A [[cloudflow.streamlets.Streamlet]] can specify the set of environment-
   * and instance-specific configuration keys it will use during runtime
   * through [[cloudflow.streamlets.Streamlet#configParameters]]. Those keys will
   * then be made available through this configuration.
   */
  final def streamletConfig: Config = context.streamletConfig

  /**
   * Java API
   */
  final def getStreamletConfig(): Config = streamletConfig

  /**
   * The streamlet reference which identifies the streamlet in the blueprint. It is used in a [[cloudflow.streamlets.Streamlet Streamlet]] for logging and metrics,
   * referring back to the streamlet instance using a name recognizable by the user.
   */
  final def streamletRef: String = context.streamletRef

  /**
   * Java API
   */
  final def getStreamletRef(): String = context.streamletRef

  /**
   * The path mounted for a VolumeMount request from a streamlet.
   * In a clustered deployment, the mounted path will correspond to the requested mount path in the
   * [[cloudflow.streamlets.VolumeMount VolumeMount]] definition.
   * In a local environment, this path will be replaced by a local folder.
   * @param volumeMount the volumeMount declaration for which we want to obtain the mounted path.
   * @return the path where the volume is mounted.
   * @throws [[cloudflow.streamlets.MountedPathUnavailableException MountedPathUnavailableException ]] in the case the path is not available.
   */
  final def getMountedPath(volumeMount: VolumeMount): Path = context.getMountedPath(volumeMount)
}
