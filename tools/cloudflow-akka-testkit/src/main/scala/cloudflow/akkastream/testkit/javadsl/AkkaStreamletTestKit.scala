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

package cloudflow.akkastream.testkit.javadsl

import java.util.{ List => JList }

import collection.JavaConverters._
import akka.NotUsed
import akka.actor._
import akka.japi.Pair
import akka.stream.javadsl._
import com.typesafe.config._
import cloudflow.akkastream._
import cloudflow.streamlets._
import cloudflow.akkastream.testkit._

import scala.annotation.varargs

object AkkaStreamletTestKit {
  def create(sys: ActorSystem): AkkaStreamletTestKit                 = AkkaStreamletTestKit(sys)
  def create(sys: ActorSystem, config: Config): AkkaStreamletTestKit = AkkaStreamletTestKit(sys, config)
}

/**
 * Java testkit for testing akkastreams streamlets.
 *
 * API:
 *
 * {{{
 * // instantiate the testkit
 * AkkaStreamletTestKit testkit = AkkaStreamletTestKit.create(system);
 *
 * // setup inlet and outlet
 * SimpleFlowProcessor sfp = new SimpleFlowProcessor();
 *
 * QueueInletTap<Data> in = testkit.makeInletAsTap(sfp.shape().inlet());
 * ProbeOutletTap<Data> out = testkit.makeOutletAsTap(sfp.shape().outlet());
 *
 * // put data
 * in.<Data>queue().offer(new Data(1, "a"));
 * in.<Data>queue().offer(new Data(2, "b"));
 *
 * // run the testkit
 * testkit.<Data, scala.Tuple2<String, Data>>run(sfp, in, out, () -> {
 *   return out.probe().expectMsg(new akka.japi.Pair<String, Data>("2", new Data(2, "b")));
 * });
 * }}}
 *
 * The following point is from `akka.testkit.Testkit` and is valid mostly for this testkit as well:
 *
 * Beware of two points:
 *
 *  - the ActorSystem passed into the constructor needs to be shutdown,
 *    otherwise thread pools and memory will be leaked
 *  - this class is not thread-safe (only one actor with one queue, one stack
 *    of `within` blocks); it is expected that the code is executed from a
 *    constructor as shown above, which makes this a non-issue, otherwise take
 *    care not to run tests within a single test class instance in parallel.
 *
 * It should be noted that for CI servers and the like all maximum Durations
 * are scaled using their Duration.dilated method, which uses the
 * TestKitExtension.Settings.TestTimeFactor settable via akka.conf entry "akka.test.timefactor".
 *
 */
final case class AkkaStreamletTestKit private[testkit] (system: ActorSystem,
                                                        config: Config = ConfigFactory.empty(),
                                                        volumeMounts: List[VolumeMount] = List.empty)
    extends BaseAkkaStreamletTestKit[AkkaStreamletTestKit] {

  def withConfig(c: Config): AkkaStreamletTestKit = this.copy(config = c)

  @varargs
  def withVolumeMounts(volumeMount: VolumeMount, volumeMounts: VolumeMount*): AkkaStreamletTestKit =
    copy(volumeMounts = volumeMount +: volumeMounts.toList)

  /**
   *
   */
  def makeInletAsTap[T](inlet: CodecInlet[T]): QueueInletTap[T] =
    QueueInletTap[T](inlet)(system)

  /**
   *
   */
  def makeInletFromSource[T](inlet: CodecInlet[T], source: Source[T, NotUsed]): SourceInletTap[T] =
    SourceInletTap[T](inlet, source.map(t => (t, TestCommittableOffset())))

  /**
   * Creates an outlet tap. An outlet tap provides a probe that can be used to assert elements produced to the specified outlet.
   *
   * The data being written to the outlet will always be partitioned using the
   * partitioner function of the outlet. This means that assertions should
   * always expect an instance of `akka.japi.Pair` with the first element being
   * the partitioning key (can be null in case the default RoundRobinPartitioner
   * is used) and the second element being the actual data element.
   *
   * Example (see the full example above, on the class level:
   *
   * {{{
   * AkkaStreamletTestKit testkit = AkkaStreamletTestKit.create(system);
   * SimpleFlowProcessor sfp = new SimpleFlowProcessor();
   * ProbeOutletTap<Data> out = testkit.makeOutletAsTap(sfp.shape().outlet());
   *
   * ...
   *
   * testkit.<Data, scala.Tuple2<String, Data>>run(sfp, in, out, () -> {
   *   return out.probe().expectMsg(new akka.japi.Pair<String, Data>("2", new Data(2, "b")));
   * });
   * }}}
   */
  def makeOutletAsTap[T](outlet: CodecOutlet[T]): ProbeOutletTap[T] =
    ProbeOutletTap[T](outlet)(system)

  /**
   * Attaches the provided Sink to the specified outlet.
   *
   * The data being written to the Sink will always be partitioned using the
   * partitioner function of the outlet. This means that the Sink should
   * expect instances of `akka.japi.Pair`, with the first element being
   * the partitioning key (can be null in case the default RoundRobinPartitioner
   * is used) and the second element being the actual data element.
   *
   * This method can be used to for instance quickly collect all output produced
   * into a simple list using `Sink.seq[T]`.
   */
  def makeOutletToSink[T](outlet: CodecOutlet[T], sink: Sink[Pair[String, T], NotUsed]): SinkOutletTap[T] =
    SinkOutletTap[T](outlet, sink)

  /**
   * Runs the `streamlet` using a list of `inletTaps` as the source and a list of `outletTaps` as the sink.
   * After running the streamlet it also runs the assertions.
   */
  def run[T](streamlet: AkkaStreamlet, inletTaps: JList[InletTap[_]], outletTaps: JList[OutletTap[_]], assertions: () => Any): Unit =
    run(streamlet, inletTaps.asScala.toList, outletTaps.asScala.toList, assertions)

  /**
   * Runs the `streamlet` using a list of `inletTaps` as the source and an `outletTap` as the sink.
   * After running the streamlet it also runs the assertions.
   */
  def run[T](streamlet: AkkaStreamlet, inletTaps: JList[InletTap[_]], outletTap: OutletTap[T], assertions: () => Any): Unit =
    run(streamlet, inletTaps.asScala.toList, List(outletTap), assertions)

  /**
   * Runs the `streamlet` using an `inlettap` as the source and a list of `outletTaps` as the sink.
   * After running the streamlet it also runs the assertions.
   */
  def run[T](streamlet: AkkaStreamlet, inletTap: InletTap[_], outletTaps: JList[OutletTap[_]], assertions: () => Any): Unit =
    run(streamlet, List(inletTap), outletTaps.asScala.toList, assertions)
}
