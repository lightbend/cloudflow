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

package taxiride.ingestor

import scala.concurrent.duration._
import akka.stream.scaladsl._
import cloudflow.streamlets.avro._
import cloudflow.streamlets._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import taxiride.avro._
import spray.json._
import TaxiFareJsonProtocol._

class KafkaFareIngress extends AkkaStreamlet {
  val in = AvroInlet[TaxiFare]("in")
  val out = AvroOutlet[TaxiFare]("out", _.rideId.toString)

  final override val shape = StreamletShape.withInlets(in).withOutlets(out)

  final override def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = sourceWithOffsetContext(in).to(committableSink(out))
  }
}
