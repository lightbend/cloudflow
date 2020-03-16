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
import scala.collection.JavaConverters._

import org.joda.time.DateTime
import cloudflow.flink.avro._

object TaxiData {
  def testRide(rideId: Long): TaxiRide =
    new TaxiRide(rideId, true, 0, 1, rideId, 0f, 0f, 0f, 0f, new DateTime(0).getMillis, new DateTime(0).getMillis)

  def testFare(rideId: Long): TaxiFare =
    new TaxiFare(rideId, 0, "", new DateTime(0).getMillis, 1, 0f, 0f, 0f)

  val ride1 = testRide(1)
  val ride2 = testRide(2)
  val fare1 = testFare(1)
  val fare2 = testFare(2)

  val rideFare1 = new TaxiRideFare(ride1.rideId, fare1.totalFare)
  val rideFare2 = new TaxiRideFare(ride2.rideId, fare2.totalFare)
  val expected  = Seq(rideFare1.toString(), rideFare2.toString()).asJava
}
