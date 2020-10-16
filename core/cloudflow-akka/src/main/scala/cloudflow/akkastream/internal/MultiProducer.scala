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

package cloudflow.akkastream.internal

import akka.NotUsed
import akka.annotation.InternalApi
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage.Committable
import akka.stream.scaladsl.{ Flow, GraphDSL, Sink, Unzip, ZipWith }
import akka.stream.{ FlowShape, Graph }
import cloudflow.akkastream.{ AkkaStreamletContext, MultiData2 }
import cloudflow.streamlets.CodecOutlet

import scala.collection.immutable

@InternalApi
private[akkastream] object MultiProducer {

  /**
   * Produce to two outlets and ensure "at-least-once" semantics by committing first after all messages
   * have been written to the designated outlets.
   */
  def flow2[O1, O2](
      outlet1: CodecOutlet[O1],
      outlet2: CodecOutlet[O2]
  )(implicit context: AkkaStreamletContext): Flow[(MultiData2[O1, O2], Committable), (Unit, Committable), NotUsed] =
    Flow
      .fromGraph(graph2(context.flexiFlow(outlet1), context.flexiFlow(outlet2)))

  /**
   * Produce to two outlets and ensure "at-least-once" semantics by committing first after all messages
   * have been written to the designated outlets.
   */
  def sink2[O1, O2](
      outlet1: CodecOutlet[O1],
      outlet2: CodecOutlet[O2],
      committerSettings: CommitterSettings
  )(implicit context: AkkaStreamletContext): Sink[(MultiData2[O1, O2], Committable), NotUsed] =
    flow2(outlet1, outlet2)
      .to(context.committableSink[Unit](committerSettings))

  private def graph2[O1, O2](
      outlet1: Flow[(immutable.Seq[O1], Committable), (Unit, Committable), _],
      outlet2: Flow[(immutable.Seq[O2], Committable), (Unit, Committable), _]
  ): Graph[akka.stream.FlowShape[(MultiData2[O1, O2], Committable), (Unit, Committable)], NotUsed] =
    GraphDSL.create(outlet1, outlet2)((_, _) => NotUsed) { implicit builder: GraphDSL.Builder[NotUsed] => (o1, o2) =>
      import GraphDSL.Implicits._

      val spreadOut = builder.add(
        Flow[(MultiData2[_ <: O1, _ <: O2], Committable)]
          .map {
            case (multi, committable) =>
              ((multi.data1, committable), (multi.data2, committable))
          }
      )
      val split = builder.add(Unzip[(immutable.Seq[O1], Committable), (immutable.Seq[O2], Committable)]())
      val keepCommittable = builder.add(ZipWith[(_, Committable), (_, Committable), (Unit, Committable)]({
        case ((_, committable), (_, _)) =>
          ((), committable)

      }))

      // format: OFF
      spreadOut ~> split.in
                   split.out0 ~> o1 ~> keepCommittable.in0
                   split.out1 ~> o2 ~> keepCommittable.in1
      // format: ON
      FlowShape(spreadOut.in, keepCommittable.out)
    }

}
