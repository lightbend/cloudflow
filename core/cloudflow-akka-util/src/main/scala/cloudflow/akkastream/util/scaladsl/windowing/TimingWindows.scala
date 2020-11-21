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

package cloudflow.akkastream.util.scaladsl.windowing

import akka.kafka.ConsumerMessage._
import akka.stream._
import akka.stream.stage._

import scala.collection.immutable.NumericRange
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

/**
 * Tumbling window implementation
 *
 * @param duration windows duration
 * @param time_extractor a function for retrieving event-time (in ms). Default is using processing time
 * @param maxsize max size for collected data. If the window length is greater then maxsize, only the last maxsize elements will be returned
 * @param watermark time to wait for late arriving events (only applicable for event-time). Here watermark is only checked
 *                  every watermakInterval, so the watermark is always rounded to watermakInterval boundry.
 * @param watermakInterval time interval for watermark testing.
 *
 * Starting time for the first window is calculated based on the time of the first recieved event.
 */
case class TumblingWindow[T](duration: FiniteDuration,
                             time_extractor: T ⇒ Long = (_: T) ⇒ System.currentTimeMillis(),
                             watermark: FiniteDuration = 0.millisecond,
                             maxsize: Int = 1000,
                             watermakInterval: FiniteDuration = 100.millisecond)
    extends GraphStage[FlowShape[(T, Committable), (Seq[T], Committable)]] {

  import TimingWindowsSupport._

  private val inlet  = Inlet[(T, Committable)]("TumblingWindow.inlet")
  private val outlet = Outlet[(Seq[T], Committable)]("TumblingWindow.outlet")

  override val shape: FlowShape[(T, Committable), (Seq[T], Committable)] = FlowShape(inlet, outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      private val buffer            = new ListBuffer[T]              // Data collector
      private val offsets           = new ListBuffer[Committable]    // Context collector
      private var windowStart: Long = -1                             // Collection start time
      private var windowEnd: Long   = -1                             // Collection end time
      private val completed         = new ListBuffer[ReadyWindow[T]] // Windows waiting for watermark

      // COnvert timings to miliseconds
      private val durationMS  = duration.toMillis
      private val watermarkMS = watermark.toMillis

      this.setHandlers(inlet, outlet, this)

      // Pre Start - start timer
      override def preStart(): Unit =
        scheduleWithFixedDelay(shape, 0.millisecond, watermakInterval)

      // Post stop - cleanup
      override def postStop(): Unit = {
        buffer.clear()
        offsets.clear()
        completed.clear()
      }

      // New event
      override def onPush(): Unit = {
        // Get element and its time
        val event = grab(inlet)
        val time  = time_extractor(event._1)
        // First time through - initial setup
        if (windowStart < 0) {
          setup(time)
        }
        if ((windowStart until windowEnd) contains time) {
          // We are in the current window the window
          updateData[T](event._1, buffer, maxsize)
        } else if (time >= windowEnd) {
          val currentTime = System.currentTimeMillis()
          // Event pass the window
          completed +=
            ReadyWindow[T](windowStart until windowEnd, buffer.clone(), maxsize, offsets.clone(), currentTime + watermarkMS)
          // Reset for next window
          setup(windowEnd)
          // Store this event
          buffer += event._1
          // Push as quickly as possible
          submitReadyWindows(currentTime)
        } else {
          // Late arriving event - store it if window is still around
          for (window ← completed) {
            if (window.range.contains(time))
              window.addLateEvent(event._1)
          }
        }
        // Regardless of where the actual data goes, the offset should go here to avoid premature commits
        offsets += event._2
        // Get next request
        if (!hasBeenPulled(inlet)) pull(inlet)
      }

      // new window request
      override def onPull(): Unit =
        if (!hasBeenPulled(inlet)) pull(inlet)

      // New timer event
      override protected def onTimer(timerKey: Any): Unit =
        // Timer event, see if we can submit some waitinfg events
        submitReadyWindows(System.currentTimeMillis())

      // Support methods
      private def setup(time: Long): Unit = {
        buffer.clear()
        offsets.clear()
        windowStart = time
        windowEnd = windowStart + durationMS
      }

      private def submitReadyWindows(currentTime: Long): Unit = for (window ← completed) {
        if (window.isReady(currentTime)) {
          val commitable = CommittableOffsetBatch(window.committable)
          println(s"Send committable $commitable")
          // Make sure to remove feom completed
          completed -= window
          emit(outlet, (window.buffer, commitable))
        }
      }
    }
}

/**
 * Sliding window implementation
 *
 * @param duration windows duration
 * @param slide time after which a new window is created
 * @param time_extractor a function for retrieving event-time (in ms). Default is using processing time
 * @param maxsize max size for collected data. If the window length is greater then maxsize, only the last maxsize elements will be returned
 * @param watermark time to wait for late arriving events (only applicable for event-time). Here watermark is only checked
 *                  every watermakInterval, so the watermark is always rounded to watermakInterval boundry.
 * @param watermakInterval time interval for watermark testing.
 *
 * Starting time for the first window is calculated based on the time of the first recieved event.
 */
case class SlidingWindow[T](duration: FiniteDuration,
                            slide: FiniteDuration,
                            time_extractor: T ⇒ Long = (_: T) ⇒ System.currentTimeMillis(),
                            watermark: FiniteDuration = 0.millisecond,
                            maxsize: Int = 1000,
                            watermakInterval: FiniteDuration = 100.millisecond)
    extends GraphStage[FlowShape[(T, Committable), (Seq[T], Committable)]] {

  private val inlet  = Inlet[(T, Committable)]("SlidingWindow.inlet")
  private val outlet = Outlet[(Seq[T], Committable)]("SlidingWindow.outlet")

  override val shape: FlowShape[(T, Committable), (Seq[T], Committable)] = FlowShape(inlet, outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      private var current: IntermediateSlidingWindow[T] = null                                         // current window
      private val active                                = new ListBuffer[IntermediateSlidingWindow[T]] // currently active Windows
      private val completed                             = new ListBuffer[ReadyWindow[T]]               // Windows waiting for watermark

      // Convert timings to milliseconds
      private val durationMS  = duration.toMillis
      private val watermarkMS = watermark.toMillis
      private val slideMS     = slide.toMillis

      // Set handlers
      this.setHandlers(inlet, outlet, this)

      // Pre Start - start timer
      override def preStart(): Unit =
        scheduleWithFixedDelay(shape, 0.millisecond, watermakInterval)

      // Post stop - cleanup
      override def postStop(): Unit = {
        current = null
        active.clear()
        completed.clear()
      }

      // New event
      override def onPush(): Unit = {
        // Get element and its time
        val event = grab(inlet)
        val time  = time_extractor(event._1)
        // First time through - initial setup
        if (current == null)
          current = IntermediateSlidingWindow[T](time, time + durationMS, time + slideMS, watermarkMS, maxsize)
        // Check whether its time to create a new window
        if (current.isNewSlidingWindow(time)) {
          current.current = false
          val startTime = current.windowSlide
          active += current
          current = IntermediateSlidingWindow[T](startTime, startTime + durationMS, startTime + slideMS, watermarkMS, maxsize)
        }

        // Add commitable to the current window
        current.committable += event._2

        // Process active windows
        val completedInThisPush = new ListBuffer[ReadyWindow[T]]
        for (window ← active)
          window.addEvent(time, event._1) match {
            case Some(readyWindow) ⇒
              completedInThisPush += readyWindow
              active -= window
            case _ ⇒
          }
        current.addEvent(time, event._1) match {
          case Some(readyWindow) ⇒
            completedInThisPush += readyWindow
            current = null
          case _ ⇒
        }

        // process late events for completed windows
        for (window ← completed) {
          if (window.range.contains(time))
            window.addLateEvent(event._1)
        }

        // move completed in this step to completed
        completed ++= completedInThisPush

        // Submit ready windows
        submitReadyWindows(System.currentTimeMillis())
        // Get next request
        if (!hasBeenPulled(inlet)) pull(inlet)
      }

      // new window request
      override def onPull(): Unit =
        if (!hasBeenPulled(inlet)) pull(inlet)

      // New timer event
      override protected def onTimer(timerKey: Any): Unit =
        // Timer event, see if we can submit some waitinfg events
        submitReadyWindows(System.currentTimeMillis())

      private def submitReadyWindows(currentTime: Long): Unit = for (window ← completed) {
        if (window.isReady(currentTime)) {
          val commitable = CommittableOffsetBatch(window.committable)
          println(s"Send committable $commitable")
          // Make sure to remove feom completed
          completed -= window
          emit(outlet, (window.buffer, commitable))
        }
      }
    }
}

/**
 * Intermediate active sliding window
 *
 * @param windowStart window's start time stamp
 * @param windowEnd window's end time stamp
 * @param windowSlide window's slide time stamp
 * @param watermark window's watermark time
 * @param current flag for the most current window
 * @param buffer window's data
 * @param maxsize max size for collected data. If the window length is greater then maxsize, only the last maxsize elements will be returned
 * @param committable Committable for this window
 */
case class IntermediateSlidingWindow[T](windowStart: Long,
                                        windowEnd: Long,
                                        windowSlide: Long,
                                        watermark: Long,
                                        maxsize: Int,
                                        buffer: ListBuffer[T] = new ListBuffer[T],
                                        committable: ListBuffer[Committable] = new ListBuffer[Committable],
                                        var current: Boolean = true) {

  import TimingWindowsSupport._

  def isNewSlidingWindow(time: Long): Boolean = time >= windowSlide

  def addEvent(time: Long, event: T): Option[ReadyWindow[T]] =
    if ((windowStart until windowEnd) contains time) {
      // We are in the current window the window
      updateData[T](event, buffer, maxsize)
      None
    } else if (time >= windowEnd) {
      val currentTime = System.currentTimeMillis()
      // Event pass the window
      Some(ReadyWindow[T](windowStart until windowEnd, buffer.clone(), maxsize, committable, currentTime + watermark))
    } else
      None
}

/**
 * Intermediate window waiting for watermark to expire
 *
 * @param range window's time range (start time until end time
 * @param buffer window's data
 * @param maxsize max size for collected data. If the window length is greater then maxsize, only the last maxsize elements will be returned
 * @param committable Committable for this window
 * @param timeToSubmit Time to submit (watermark expiration)
 */
case class ReadyWindow[T](range: NumericRange[Long],
                          buffer: ListBuffer[T],
                          maxsize: Int,
                          committable: ListBuffer[Committable],
                          timeToSubmit: Long) {

  import TimingWindowsSupport._
  def addLateEvent(event: T): Unit =
    updateData[T](event, buffer, maxsize)
  def isReady(currentTime: Long): Boolean = currentTime >= timeToSubmit
}

object TimingWindowsSupport {

  def updateData[T](event: T, b: ListBuffer[T], max: Int): Unit = {
    if (b.size >= max)
      b.remove(0) // Ensure maxsize of buffere
    b += event
  }
}
