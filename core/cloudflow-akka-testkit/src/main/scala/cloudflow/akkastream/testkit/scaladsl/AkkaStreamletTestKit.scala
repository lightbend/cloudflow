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

package cloudflow.akkastream.testkit.scaladsl

import akka.NotUsed
import akka.actor._
import akka.stream.scaladsl._
import com.typesafe.config._

import cloudflow.streamlets._

import cloudflow.akkastream.testkit._

object AkkaStreamletTestKit {
  def apply(sys: ActorSystem): AkkaStreamletTestKit                 = new AkkaStreamletTestKit(sys)
  def apply(sys: ActorSystem, config: Config): AkkaStreamletTestKit = new AkkaStreamletTestKit(sys, config)
}

/**
 * Testkit for testing akka streamlets.
 *
 * API:
 *
 * {{{
 * // instantiate the testkit
 * val testkit = AkkaStreamletTestKit(system)
 *
 * // setup inlet and outlet
 * val in = testkit.inletAsQueue(SimpleFlowProcessor.shape.inlet)
 * val out = testkit.outletAsProbe(SimpleFlowProcessor.shape.outlet)
 *
 * // put data
 * in.queue.offer(Data(1, "a"))
 * in.queue.offer(Data(2, "b"))
 *
 * // run the testkit
 * testkit.run(SimpleFlowProcessor, in, out, () => {
 *   out.probe.expectMsg(("2", Data(2, "b")))
 * })
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

  def withVolumeMounts(volumeMount: VolumeMount, volumeMounts: VolumeMount*): AkkaStreamletTestKit =
    copy(volumeMounts = volumeMount +: volumeMounts.toList)

  /**
   *
   */
  def inletAsTap[T](inlet: CodecInlet[T]): QueueInletTap[T] =
    QueueInletTap[T](inlet)(system)

  /**
   *
   */
  def inletFromSource[T](inlet: CodecInlet[T], source: Source[T, NotUsed]): SourceInletTap[T] =
    SourceInletTap[T](inlet, source.map(t => (t, TestCommittableOffset())))

  /**
   * Creates an outlet tap. An outlet tap provides a probe that can be used to assert elements produced to the specified outlet.
   *
   * The data being written to the outlet will always be partitioned using the
   * partitioner function of the outlet. This means that assertions should
   * always expect a Scala tuple with the first element being
   * the partitioning key (can be null in case the default RoundRobinPartitioner
   * is used) and the second element being the actual data element.
   *
   * Example (see the full example above, on the class level:
   *
   * {{{
   * val testkit = AkkaStreamletTestKit(system)
   * val out = testkit.outletAsProbe(SimpleFlowProcessor.shape.outlet)
   *
   * ...
   *
   * testkit.run(SimpleFlowProcessor, in, out, () => {
   *   out.probe.expectMsg(("2", Data(2, "b")))
   * })
   * }}}
   */
  def outletAsTap[T](outlet: CodecOutlet[T]): ProbeOutletTap[T] =
    ProbeOutletTap[T](outlet)(system)

  /**
   * Attaches the provided Sink to the specified outlet.
   *
   * The data being written to the Sink will always be partitioned using the
   * partitioner function of the outlet. This means that the Sink should
   * expect Scala tuples, with the first element being the partitioning key
   * (can be null in case the default RoundRobinPartitioner is used) and the
   * second element being the actual data element.
   *
   * This method can be used to for instance quickly collect all output produced
   * into a simple sequence using `Sink.seq[T]`.
   */
  def outletToSink[T](outlet: CodecOutlet[T], sink: Sink[Tuple2[String, T], NotUsed]): SinkOutletTap[T] =
    SinkOutletTap[T](outlet, sink)
}
