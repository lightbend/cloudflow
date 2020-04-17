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

/*
 * Copyright 2018 data Artisans GmbH, 2019 Ververica GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package taxiride.processor

import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.functions.co._
import org.apache.flink.api.common.state.{ ValueState, ValueStateDescriptor }
import org.apache.flink.util.Collector

import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import taxiride.datamodel._
import cloudflow.flink._

class TaxiRideProcessor extends FlinkStreamlet {

  // Step 1: Define inlets and outlets. Note for the outlet you need to specify
  //         the partitioner function explicitly : here we are using the
  //         rideId as the partitioner
  @transient val inTaxiRide = AvroInlet[TaxiRide]("in-taxiride")
  @transient val inTaxiFare = AvroInlet[TaxiFare]("in-taxifare")
  @transient val out        = AvroOutlet[TaxiRideFare]("out", _.rideId.toString)

  // Step 2: Define the shape of the streamlet. In this example the streamlet
  //         has 2 inlets and 1 outlet
  @transient val shape = StreamletShape.withInlets(inTaxiRide, inTaxiFare).withOutlets(out)

  // Step 3: Provide custom implementation of `FlinkStreamletLogic` that defines
  //         the behavior of the streamlet
  override def createLogic() = new FlinkStreamletLogic {
    override def buildExecutionGraph = {
      val rides: DataStream[TaxiRide] =
        readStream(inTaxiRide)
          .filter { ride ⇒
            ride.isStart.booleanValue
          }
          .keyBy("rideId")

      val fares: DataStream[TaxiFare] =
        readStream(inTaxiFare)
          .keyBy("rideId")

      val processed: DataStream[TaxiRideFare] =
        rides
          .connect(fares)
          .flatMap(new EnrichmentFunction)

      writeStream(out, processed)
    }
  }

  import org.apache.flink.configuration.Configuration
  class EnrichmentFunction extends RichCoFlatMapFunction[TaxiRide, TaxiFare, TaxiRideFare] {

    @transient var rideState: ValueState[TaxiRide] = null
    @transient var fareState: ValueState[TaxiFare] = null

    override def open(params: Configuration): Unit = {
      super.open(params)
      rideState = getRuntimeContext.getState(new ValueStateDescriptor[TaxiRide]("saved ride", classOf[TaxiRide]))
      fareState = getRuntimeContext.getState(new ValueStateDescriptor[TaxiFare]("saved fare", classOf[TaxiFare]))
    }

    override def flatMap1(ride: TaxiRide, out: Collector[TaxiRideFare]): Unit = {
      val fare = fareState.value
      if (fare != null) {
        fareState.clear()
        out.collect(new TaxiRideFare(ride.rideId, fare.totalFare))
      } else {
        rideState.update(ride)
      }
    }

    override def flatMap2(fare: TaxiFare, out: Collector[TaxiRideFare]): Unit = {
      val ride = rideState.value
      if (ride != null) {
        rideState.clear()
        out.collect(new TaxiRideFare(ride.rideId, fare.totalFare))
      } else {
        fareState.update(fare)
      }
    }
  }
}
