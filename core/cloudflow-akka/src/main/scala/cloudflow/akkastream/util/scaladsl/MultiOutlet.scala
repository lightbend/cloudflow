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

package cloudflow.akkastream.util.scaladsl

import akka.NotUsed
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage.Committable
import akka.stream.scaladsl.{ Flow, GraphDSL, Sink, Unzip, ZipWith }
import akka.stream.{ FlowShape, Graph }
import cloudflow.akkastream.AkkaStreamletContext
import cloudflow.streamlets.CodecOutlet

import scala.collection.immutable

object MultiOutlet {

  /**
   * Produce to two outlets and ensure "at-least-once" semantics by committing first after all messages
   * have been written to the designated outlets.
   */
  def sink2[O1, O2](
      outlet1: CodecOutlet[O1],
      outlet2: CodecOutlet[O2],
      committerSettings: CommitterSettings
  )(implicit context: AkkaStreamletContext): Sink[((immutable.Seq[O1], immutable.Seq[O2]), Committable), NotUsed] =
    Flow
      .fromGraph(graph2(context.flexiFlow(outlet1), context.flexiFlow(outlet2)))
      .to(context.committableSink[Unit](committerSettings))

  /**
   * Produce to three outlets and ensure "at-least-once" semantics by committing first after all messages
   * have been written to the designated outlets.
   */
  def sink3[O1, O2, O3](
      outlet1: CodecOutlet[O1],
      outlet2: CodecOutlet[O2],
      outlet3: CodecOutlet[O3],
      committerSettings: CommitterSettings
  )(implicit context: AkkaStreamletContext): Sink[((immutable.Seq[O1], immutable.Seq[O2], immutable.Seq[O3]), Committable), NotUsed] =
    Flow
      .fromGraph(graph3(context.flexiFlow(outlet1), context.flexiFlow(outlet2), context.flexiFlow(outlet3)))
      .to(context.committableSink[Unit](committerSettings))

  private def graph2[O1, O2](
      outlet1: Flow[(immutable.Seq[O1], Committable), (_, Committable), _],
      outlet2: Flow[(immutable.Seq[O2], Committable), (_, Committable), _]
  ): Graph[akka.stream.FlowShape[((immutable.Seq[O1], immutable.Seq[O2]), Committable), (Unit, Committable)], NotUsed] =
    GraphDSL.create(outlet1, outlet2)((_, _) => NotUsed) { implicit builder: GraphDSL.Builder[NotUsed] => (o1, o2) =>
      import GraphDSL.Implicits._

      val spreadOut = builder.add(
        Flow[((immutable.Seq[O1], immutable.Seq[O2]), Committable)]
          .map {
            case ((seq1, seq2), committable) =>
              ((seq1, committable), (seq2, committable))
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

  private def graph3[O1, O2, O3](
      outlet1: Flow[(immutable.Seq[O1], Committable), (_, Committable), _],
      outlet2: Flow[(immutable.Seq[O2], Committable), (_, Committable), _],
      outlet3: Flow[(immutable.Seq[O3], Committable), (_, Committable), _]
  ): Graph[akka.stream.FlowShape[((immutable.Seq[O1], immutable.Seq[O2], immutable.Seq[O3]), Committable), (Unit, Committable)], NotUsed] =
    GraphDSL.create(outlet1, outlet2, outlet3)((_, _, _) => NotUsed) { implicit builder: GraphDSL.Builder[NotUsed] => (o1, o2, o3) =>
      import GraphDSL.Implicits._

      val spreadOut = builder.add(
        Flow[((immutable.Seq[O1], immutable.Seq[O2], immutable.Seq[O3]), Committable)]
          .map {
            case ((seq1, seq2, seq3), committable) =>
              ((seq1, committable), ((seq2, committable), (seq3, committable)))
          }
      )
      val split1 =
        builder.add(Unzip[(immutable.Seq[O1], Committable), ((immutable.Seq[O2], Committable), (immutable.Seq[O3], Committable))]())
      val split2 = builder.add(Unzip[(immutable.Seq[O2], Committable), (immutable.Seq[O3], Committable)]())
      val keepCommittable = builder.add(ZipWith[(_, Committable), (_, Committable), (_, Committable), (Unit, Committable)]({
        case ((_, committable), (_, _), (_, _)) =>
          ((), committable)
      }))
      // format: OFF
      spreadOut ~> split1.in
                   split1.out0 ~> o1 ~> keepCommittable.in0
                   split1.out1 ~> split2.in
                                  split2.out0 ~> o2 ~> keepCommittable.in1
                                  split2.out1 ~> o3 ~> keepCommittable.in2
      // format: ON
      FlowShape(spreadOut.in, keepCommittable.out)
    }

}
