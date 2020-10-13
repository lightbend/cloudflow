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

import cloudflow.flink._
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto.ProtoOutlet
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.functions.source.SourceFunction
import taxiride.datamodel._
import spray.json._
import TaxiRideJsonProtocol._

class RidesGenerator extends FlinkStreamlet {

  val ridesOut = ProtoOutlet[TaxiRide]("rides", _.rideId.toString)
  val faresOut = ProtoOutlet[TaxiFare]("fares", _.rideId.toString)
  val shape = StreamletShape.withOutlets(ridesOut, faresOut)

  override protected def createLogic(): FlinkStreamletLogic = new FlinkStreamletLogic {
    override def buildExecutionGraph: Unit = {
      val rides = context.env.addSource(new TaxiRideRidesSource())
      val fares = context.env.addSource(new TaxiRideFaresSource())

      writeStream(ridesOut, rides)
      writeStream(faresOut, fares)
    }
  }
}

class TaxiRideRidesSource extends SourceFunction[TaxiRide] {

  private var isRunning = true
  private val rides = readRides()
  private var pos = 0

  override def run(ctx: SourceFunction.SourceContext[TaxiRide]): Unit = {
    while (isRunning) {
      Thread.sleep(20)
      // We can't use iterator here, as it is not serializable
      if (pos >= rides.size)
        pos = 0
      ctx.collect(rides(pos))
      pos = pos + 1
    }
  }

  override def cancel(): Unit = {
    isRunning = false
  }

  def readRides(): List[TaxiRide] = {
    println("Reading rides")
    val inRides = this.getClass.getResourceAsStream("/nycTaxiRides.json")
    // 'fixing' JSON issues in input document
    val str       = scala.io.Source.fromInputStream(inRides).mkString
    val ridesJson = s"[$str]".replaceAll("\n", ",\n").parseJson
    val rides     = ridesJson.convertTo[List[TaxiRide]]
    println(s"Read ${rides.size} rides")
    rides
  }
}

class TaxiRideFaresSource extends SourceFunction[TaxiFare] {

  import TaxiFareJsonProtocol._

  private var isRunning = true
  private val fares = readFares()
  private var pos = 0

  override def run(ctx: SourceFunction.SourceContext[TaxiFare]): Unit = {
    println(s"Fare source - run method - $isRunning")
    while (isRunning) {
      Thread.sleep(20)
      // We can't use iterator here, as it is not serializable
      if (pos >= fares.size)
        pos = 0
      ctx.collect(fares(pos))
      pos = pos + 1
    }
  }

  override def cancel(): Unit = {
    isRunning = false
  }

  private def readFares(): List[TaxiFare] = {
    println("Reading fares")
    val inFares = this.getClass.getResourceAsStream("/nycTaxiFares.json")
    // 'fixing' JSON issues in input document
    val str = scala.io.Source.fromInputStream(inFares).mkString
    val faresJson = s"[$str]".replaceAll("\n", ",\n").parseJson
    val fares = faresJson.convertTo[List[TaxiFare]]
    println(s"Read ${fares.size} fares")
    fares
  }
}