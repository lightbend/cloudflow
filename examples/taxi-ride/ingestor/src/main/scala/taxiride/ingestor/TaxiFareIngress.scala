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

package taxiride.ingestor

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import cloudflow.streamlets.avro._
import cloudflow.streamlets._
import cloudflow.akkastream._
import taxiride.datamodel._
import TaxiFareJsonProtocol._
import cloudflow.akkastream.util.scaladsl.HttpServerLogic

class TaxiFareIngress extends AkkaServerStreamlet {
  val out = AvroOutlet[TaxiFare]("out", _.rideId.toString)

  final override val shape       = StreamletShape.withOutlets(out)
  final override def createLogic = HttpServerLogic.default(this, out)
}
