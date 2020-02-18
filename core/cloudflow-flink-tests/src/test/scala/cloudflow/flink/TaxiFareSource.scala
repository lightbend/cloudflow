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

import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Calendar
import java.util.PriorityQueue
import java.util.Random
import java.util.zip.GZIPInputStream

import scala.util.{ Failure, Success, Try }

import org.apache.flink.streaming.api.functions.source.SourceFunction
import SourceFunction.SourceContext
import cloudflow.flink.avro._
import org.apache.flink.streaming.api.watermark.Watermark

case class TaxiFareSource(
    dataFilePath: String,
    maxEventDelaySecs: Int,
    servingSpeedFactor: Int
) extends SourceFunction[TaxiFare] {

  val maxDelayMsecs       = maxEventDelaySecs * 1000
  val watermarkDelayMSecs = if (maxDelayMsecs < 10000) 10000 else maxDelayMsecs
  val servingSpeed        = servingSpeedFactor

  @transient private var gzipStream: InputStream = _
  @transient private var reader: BufferedReader  = _

  override def run(sourceContext: SourceContext[TaxiFare]): Unit = {
    gzipStream = new GZIPInputStream(new FileInputStream(dataFilePath))
    reader = new BufferedReader(new InputStreamReader(gzipStream, "UTF-8"))

    generateUnorderedStream(sourceContext)

    this.reader.close()
    this.reader = null
    this.gzipStream.close()
    this.gzipStream = null
  }

  private def getNextFare(): Try[TaxiFare] =
    if (!reader.ready) Failure(new RuntimeException("Reader not yet ready"))
    else {
      val line = reader.readLine()
      if (line == null) Failure(new RuntimeException("Encountered null record"))
      else TaxiFareOps.fromString(line)
    }

  private def generateUnorderedStream(sourceContext: SourceContext[TaxiFare]): Unit = {

    val rand                = new Random(7452)
    val servingStartTime    = Calendar.getInstance().getTimeInMillis()
    var dataStartTime: Long = 0L

    val emitSchedule: PriorityQueue[(Long, Any)] = new PriorityQueue(
      32,
      (o1, o2) ⇒ o1._1.compare(o2._1)
    )

    def readFirstRideAndUpdateEmitSchedule(emitSchedule: PriorityQueue[(Long, Any)], rand: Random): Unit =
      getNextFare() match {
        case Success(fare) ⇒
          // extract starting timestamp
          dataStartTime = getEventTime(fare)

          // get delayed time
          val delayedEventTime = dataStartTime + getNormalDelayMsecs(rand)

          emitSchedule.add((delayedEventTime, fare))

          // schedule next watermark
          val watermarkTime = dataStartTime + watermarkDelayMSecs
          val nextWatermark = new Watermark(watermarkTime - maxDelayMsecs - 1)
          emitSchedule.add((watermarkTime, nextWatermark))

        case Failure(ex) ⇒ throw ex
      }

    readFirstRideAndUpdateEmitSchedule(emitSchedule, rand)

    getNextFare() match {
      case Success(f) ⇒ {
        var fare = f

        // read rides one-by-one and emit a random ride from the buffer each time
        while (emitSchedule.size() > 0 || reader.ready()) {

          // insert all events into schedule that might be emitted next
          val curNextDelayedEventTime = if (!emitSchedule.isEmpty()) emitSchedule.peek()._1 else -1
          var rideEventTime           = if (fare != null) getEventTime(fare) else -1
          while (fare != null && (// while there is a ride AND
                 emitSchedule.isEmpty() ||                                // and no ride in schedule OR
                 rideEventTime < curNextDelayedEventTime + maxDelayMsecs) // not enough rides in schedule
                 ) {
            // insert event into emit schedule
            val delayedEventTime = rideEventTime + getNormalDelayMsecs(rand)
            emitSchedule.add((delayedEventTime, fare))

            getNextFare() match {
              case Success(f) ⇒ {
                fare = f
                rideEventTime = getEventTime(fare)
              }
              case Failure(_) ⇒ {
                rideEventTime = -1
                fare = null
              }
            }
          }

          // emit schedule is updated, emit next element in schedule
          val head             = emitSchedule.poll()
          val delayedEventTime = head._1

          val now         = Calendar.getInstance().getTimeInMillis()
          val servingTime = toServingTime(servingStartTime, dataStartTime, delayedEventTime)
          val waitTime    = servingTime - now

          Thread.sleep(if (waitTime > 0) waitTime else 0)

          head._2 match {
            case emitRide: TaxiFare ⇒ sourceContext.collectWithTimestamp(emitRide, getEventTime(emitRide))
            case emitWatermark: Watermark ⇒ {
              sourceContext.emitWatermark(emitWatermark)
              // schedule next watermark
              val watermarkTime = delayedEventTime + watermarkDelayMSecs
              val nextWatermark = new Watermark(watermarkTime - maxDelayMsecs - 1)
              emitSchedule.add((watermarkTime, nextWatermark))
            }
            case _ ⇒ throw new RuntimeException("Unexpected data found. TaxiFare or Watermark expected")
          }
        }
      }
      case Failure(ex) ⇒ throw ex
    }

  }

  def toServingTime(servingStartTime: Long, dataStartTime: Long, eventTime: Long): Long = {
    val dataDiff = eventTime - dataStartTime
    servingStartTime + (dataDiff / this.servingSpeed)
  }

  def getEventTime(fare: TaxiFare): Long = TaxiFareOps.getEventTime(fare)

  def getNormalDelayMsecs(rand: Random): Long = {
    var delay   = -1L
    val x: Long = maxDelayMsecs / 2

    while (delay < 0 || delay > maxDelayMsecs) {
      delay = (rand.nextGaussian() * x).asInstanceOf[Long] + x
    }
    delay
  }

  override def cancel(): Unit =
    Try {
      if (reader != null) reader.close()
      if (gzipStream != null) gzipStream.close()
    }.transform(
        s ⇒ Success(s),
        ioe ⇒ Failure(new RuntimeException("Could not cancel SourceFunction", ioe))
      )
      .get

}
