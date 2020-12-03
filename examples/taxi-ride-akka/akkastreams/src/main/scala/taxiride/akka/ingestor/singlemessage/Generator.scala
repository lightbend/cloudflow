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

package taxiride.akka.ingestor.singlemessage

import akka.stream.scaladsl._
import cloudflow.akkastream._
import cloudflow.streamlets._
import cloudflow.streamlets.proto._
import spray.json._
import taxiride.akka.ingestor._
import taxiride.datamodel.TaxiRideOrFare._
import taxiride.datamodel._

import scala.concurrent.duration._

class Generator extends AkkaStreamlet {
  val messageOut = ProtoOutlet[TaxiRideOrFare]("data", _.rideId.toString)

  val Throttle = IntegerConfigParameter("throttle", "Generate max X records per second.", Some(50))

  override def configParameters = Vector(Throttle)

  final override val shape = StreamletShape.withOutlets(messageOut)
  final override def createLogic = new AkkaStreamletLogic() {
    val throttleElements = Throttle.value
    println(s"Throttling fares and rides to $throttleElements/s")
    var isfare = true
    override def run() = {
      val fares = readFares()
      val rides = readRides()
      Source
        .cycle { () =>
          if(isfare){
            isfare = false
            fares.iterator.map(f => TaxiRideOrFare(rideId = f.rideId, messageType = MessageType.Fare(f)))
          }
          else{
            isfare = true
            rides.iterator.map(r => TaxiRideOrFare(rideId = r.rideId, messageType = MessageType.Ride(r)))
          }
        }
        .throttle(throttleElements, 1.seconds)
        .to(plainSink(messageOut))
        .run
    }
  }

  def readFares(): List[TaxiFare] = {
    import TaxiFareJsonProtocol._
    val inFares = this.getClass.getResourceAsStream("/nycTaxiFares.json")
    // 'fixing' JSON issues in input document
    val str       = scala.io.Source.fromInputStream(inFares).mkString
    val faresJson = s"[$str]".replaceAll("\n", ",\n").parseJson
    val fares     = faresJson.convertTo[List[TaxiFare]]
    println(s"Read ${fares.size} fares")
    fares
  }

  def readRides(): List[TaxiRide] = {
    import TaxiRideJsonProtocol._
    val inRides = this.getClass.getResourceAsStream("/nycTaxiRides.json")
    // 'fixing' JSON issues in input document
    val str       = scala.io.Source.fromInputStream(inRides).mkString
    val ridesJson = s"[$str]".replaceAll("\n", ",\n").parseJson
    val rides     = ridesJson.convertTo[List[TaxiRide]]
    println(s"Read ${rides.size} rides")
    rides
  }
}
