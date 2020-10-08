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

package cloudflow.akkastream.util.javadsl

import akka.NotUsed
import akka.japi.Pair
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage.Committable
import akka.stream.javadsl
import cloudflow.akkastream.AkkaStreamletContext
import cloudflow.akkastream.util.scaladsl
import cloudflow.streamlets.CodecOutlet

import scala.jdk.CollectionConverters._

object MultiOutlet {

  /**
   * Produce to two outlets and ensure "at-least-once" semantics by committing first after all messages
   * have been written to the designated outlets.
   */
  def sink2[O1, O2](
      outlet1: CodecOutlet[O1],
      outlet2: CodecOutlet[O2],
      committerSettings: CommitterSettings
  )(implicit context: AkkaStreamletContext): javadsl.Sink[Pair[Pair[java.util.List[O1], java.util.List[O2]], Committable], NotUsed] =
    akka.stream.scaladsl
      .Flow[Pair[Pair[java.util.List[O1], java.util.List[O2]], Committable]]
      .map { pairs =>
        ((pairs.first.first.asScala.toIndexedSeq, pairs.first.second.asScala.toIndexedSeq), pairs.second)
      }
      .to(scaladsl.MultiOutlet.sink2(outlet1, outlet2, committerSettings))
      .asJava

  /**
   * Produce to three outlets and ensure "at-least-once" semantics by committing first after all messages
   * have been written to the designated outlets.
   */
  def sink3[O1, O2, O3](
      outlet1: CodecOutlet[O1],
      outlet2: CodecOutlet[O2],
      outlet3: CodecOutlet[O3],
      committerSettings: CommitterSettings
  )(
      implicit context: AkkaStreamletContext
  ): javadsl.Sink[Pair[Pair[java.util.List[O1], Pair[java.util.List[O2], java.util.List[O3]]], Committable], NotUsed] =
    akka.stream.scaladsl
      .Flow[Pair[Pair[java.util.List[O1], Pair[java.util.List[O2], java.util.List[O3]]], Committable]]
      .map { pairs =>
        ((pairs.first.first.asScala.toIndexedSeq,
          pairs.first.second.first.asScala.toIndexedSeq,
          pairs.first.second.second.asScala.toIndexedSeq),
         pairs.second)
      }
      .to(scaladsl.MultiOutlet.sink3(outlet1, outlet2, outlet3, committerSettings))
      .asJava

}
