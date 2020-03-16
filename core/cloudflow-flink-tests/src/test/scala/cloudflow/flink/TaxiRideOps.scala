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

import java.util.Locale

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.util.{ Failure, Success, Try }

import cloudflow.flink.avro._

object TaxiRideOps {

  @transient val timeFormatter =
    DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").withLocale(Locale.US).withZoneUTC();

  def fromString(ride: String): Try[TaxiRide] = {
    def parseFloat(s: String) =
      if (s.length() > 0) s.toFloat else 0.0f

    def parseDateTime(s: String) =
      DateTime.parse(s, timeFormatter)

    val tokens = ride.split(",")
    if (tokens.length != 11) Failure(new RuntimeException(s"Invalid record: $ride"))
    else
      Try {
        val rideId = tokens(0).toLong

        val (isStart, startTime, endTime) = tokens(1) match {
          case "START" ⇒ (true, parseDateTime(tokens(2)), parseDateTime(tokens(3)))
          case "END"   ⇒ (false, parseDateTime(tokens(3)), parseDateTime(tokens(2)))
          case _       ⇒ throw new RuntimeException(s"Invalid record: $ride")
        }

        new TaxiRide(
          rideId,
          isStart,
          tokens(9).toLong,
          tokens(8).toShort,
          tokens(10).toLong,
          parseFloat(tokens(4)),
          parseFloat(tokens(5)),
          parseFloat(tokens(6)),
          parseFloat(tokens(7)),
          startTime.getMillis(),
          endTime.getMillis()
        )
      }.transform(s ⇒ Success(s), e ⇒ Failure(new RuntimeException(s"Invalid record: $ride", e)))
  }

  def getEventTime(ride: TaxiRide): Long =
    if (ride.isStart) ride.startTime else ride.endTime

}
