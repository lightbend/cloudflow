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

object TaxiFareOps {

  @transient val timeFormatter =
    DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").withLocale(Locale.US).withZoneUTC();

  def fromString(fare: String): Try[TaxiFare] = {
    def parseFloat(s: String) =
      if (s.length() > 0) s.toFloat else 0.0f

    def parseDateTime(s: String) =
      DateTime.parse(s, timeFormatter)

    val tokens = fare.split(",")
    if (tokens.length != 8) Failure(new RuntimeException(s"Invalid record: $fare"))
    else
      Try {
        val rideId = tokens(0).toLong

        new TaxiFare(
          rideId,
          tokens(1).toLong,
          tokens(4),
          tokens(2).toLong,
          parseDateTime(tokens(3)).getMillis(),
          parseFloat(tokens(5)),
          parseFloat(tokens(6)),
          parseFloat(tokens(7))
        )
      }.transform(s ⇒ Success(s), e ⇒ Failure(new RuntimeException(s"Invalid record: $fare", e)))
  }

  def getEventTime(fare: TaxiFare): Long = fare.startTime
}
