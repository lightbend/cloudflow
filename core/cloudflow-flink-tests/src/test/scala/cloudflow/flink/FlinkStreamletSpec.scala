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

package cloudflow.flink

import scala.collection.immutable.Seq

import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.TimeCharacteristic

import cloudflow.flink.avro._
import cloudflow.flink.testkit._
import org.scalatest._

class FlinkStreamletSpec extends FlinkTestkit with WordSpecLike with Matchers with BeforeAndAfterAll {

  "FlinkProcessor" should {
    "process streaming data" in {
      @transient lazy val env = StreamExecutionEnvironment.getExecutionEnvironment

      // build data and send to inlet tap
      val data = (1 to 10).map(i ⇒ new Data(i, s"name$i"))

      // setup inlet tap on inlet port
      val in: FlinkInletTap[Data] = inletAsTap[Data](
        FlinkProcessor.in,
        env.addSource(FlinkSource.CollectionSourceFunction(data)))

      // setup outlet tap on outlet port
      val out: FlinkOutletTap[Simple] = outletAsTap[Simple](FlinkProcessor.out)

      run(FlinkProcessor, Seq(in), Seq(out), env)

      TestFlinkStreamletContext.result should contain((new Simple("name1")).toString())
      TestFlinkStreamletContext.result.size should equal(10)
    }
  }

  "FlinkConnectedProcessor" should {
    "process streaming data that arrives in order" in {

      val env = StreamExecutionEnvironment.getExecutionEnvironment
      env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
      env.setParallelism(4)

      import TaxiData._

      // setup inlet tap on inlet port

      val inRides: FlinkInletTap[TaxiRide] = inletAsTap[TaxiRide](
        FlinkConnectedProcessor.inTaxiRide,
        env.addSource(FlinkSource.CollectionSourceFunction(Seq(ride1, ride2))))

      val inFares: FlinkInletTap[TaxiFare] = inletAsTap[TaxiFare](
        FlinkConnectedProcessor.inTaxiFare,
        env.addSource(FlinkSource.CollectionSourceFunction(Seq(fare1, fare2))))

      // setup outlet tap on outlet port
      val out: FlinkOutletTap[TaxiRideFare] = outletAsTap[TaxiRideFare](FlinkConnectedProcessor.out)

      run(FlinkConnectedProcessor, Seq(inRides, inFares), Seq(out), env)

      TestFlinkStreamletContext.result.size should equal(2)
      TestFlinkStreamletContext.result.containsAll(expected) should equal(true)
    }

    "process streaming data that arrives out of order" in {

      val env = StreamExecutionEnvironment.getExecutionEnvironment
      env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
      env.setParallelism(4)

      import TaxiData._

      // setup inlet tap on inlet port

      val inRides: FlinkInletTap[TaxiRide] = inletAsTap[TaxiRide](
        FlinkConnectedProcessor.inTaxiRide,
        env.addSource(FlinkSource.CollectionSourceFunction(Seq(ride1, ride2))))

      val inFares: FlinkInletTap[TaxiFare] = inletAsTap[TaxiFare](
        FlinkConnectedProcessor.inTaxiFare,
        env.addSource(FlinkSource.CollectionSourceFunction(Seq(fare2, fare1))))

      // setup outlet tap on outlet port
      val out: FlinkOutletTap[TaxiRideFare] = outletAsTap[TaxiRideFare](FlinkConnectedProcessor.out)

      run(FlinkConnectedProcessor, Seq(inRides, inFares), Seq(out), env)

      TestFlinkStreamletContext.result.size should equal(2)
      TestFlinkStreamletContext.result.containsAll(expected) should equal(true)
    }
  }

  "FlinkConnectedProcessor with streaming data source" ignore {
    "process streaming data" in {

      val env = StreamExecutionEnvironment.getExecutionEnvironment
      env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
      env.setParallelism(4)

      // setup inlet tap on inlet port
      val delay = 60; // at most 60 seconds of delay
      val servingSpeedFactor = 1800 // 30 minutes worth of events are served every second

      val inRides: FlinkInletTap[TaxiRide] = inletAsTap[TaxiRide](
        FlinkConnectedProcessor.inTaxiRide,
        env.addSource(TaxiRideSource("flink-tests/src/test/resources/nycTaxiRidesMini.gz", delay, servingSpeedFactor)))

      val inFares: FlinkInletTap[TaxiFare] = inletAsTap[TaxiFare](
        FlinkConnectedProcessor.inTaxiFare,
        env.addSource(TaxiFareSource("flink-tests/src/test/resources/nycTaxiFaresMini.gz", delay, servingSpeedFactor)))

      // setup outlet tap on outlet port
      val out: FlinkOutletTap[TaxiRideFare] = outletAsTap[TaxiRideFare](FlinkConnectedProcessor.out)

      run(FlinkConnectedProcessor, Seq(inRides, inFares), Seq(out), env)

      println(TestFlinkStreamletContext.result.size)
      1 should equal(1)
    }
  }
}
