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

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

/**
 * Session Window based on inactivity
 *
 * This type of window treats session as inactivity between data arrival -
 * see this blog post https://efekahraman.github.io/2019/01/session-windows-in-akka-streams
 *
 * @param inactivity time of inactivity constituting end of the session
 * @param time_extractor a function for retrieving event-time (in ms). Default is using processing time
 * @param maxsize max size for collected data. If the window length is greater then maxsize, only the last maxsize elements will be returned
 * @param inactivityInterval time interval for inactivity testing.
 *
 * Starting time for the first window is calculated based on the time of the first recieved event.
 */
case class SessionInactivityWindow[T](inactivity: FiniteDuration,
                                      time_extractor: T ⇒ Long = (_: T) ⇒ System.currentTimeMillis(),
                                      maxsize: Int = 1000,
                                      inactivityInterval: FiniteDuration = 100.millisecond)
    extends GraphStage[FlowShape[(T, Committable), (Seq[T], Committable)]] {

  import TimingWindowsSupport._

  private val inlet  = Inlet[(T, Committable)]("SessionInactivityWindow.inlet")
  private val outlet = Outlet[(Seq[T], Committable)]("SessionInactivityWindow.outlet")

  override val shape: FlowShape[(T, Committable), (Seq[T], Committable)] = FlowShape(inlet, outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      private val buffer            = new ListBuffer[T]           // Data collector
      private val offsets           = new ListBuffer[Committable] // Context collector
      private var lastTS: Long      = -1                          // last ts
      private var lastlocalTS: Long = -1                          // last local ts

      // COnvert timings to miliseconds
      private val inactivityMS = inactivity.toMillis

      this.setHandlers(inlet, outlet, this)

      // Pre Start - start timer
      override def preStart(): Unit =
        scheduleWithFixedDelay(shape, 0.millisecond, inactivityInterval)

      // Post stop - cleanup
      override def postStop(): Unit = {
        buffer.clear()
        offsets.clear()
      }

      // New event
      override def onPush(): Unit = {
        // Get element and its time
        val event = grab(inlet)
        val time  = time_extractor(event._1)

        // Check whether this is a new window
        if ((lastTS > 0) && (time > (lastTS + inactivityMS))) {
          submitReadyWindows()
        }

        // Add event
        updateData[T](event._1, buffer, maxsize)
        offsets += event._2
        lastTS = time
        lastlocalTS = System.currentTimeMillis()

        // Ask for new event
        if (!hasBeenPulled(inlet)) pull(inlet)
      }

      // new window request
      override def onPull(): Unit =
        if (!hasBeenPulled(inlet)) pull(inlet)

      // New timer event
      override protected def onTimer(timerKey: Any): Unit = {
        // Timer event, see if we can submit some waitinfg events
        val time = System.currentTimeMillis()
        if ((lastTS > 0) && ((time - lastlocalTS) > inactivityMS))
          submitReadyWindows()
      }

      private def submitReadyWindows(): Unit = {
        val commitable = CommittableOffsetBatch(offsets.clone())
        println(s"Send committable $commitable")
        val result = (buffer.clone(), commitable)
        buffer.clear()
        offsets.clear()
        lastTS = -1
        emit(outlet, result)
      }
    }
}

/**
 * Session Window based on the session value
 *
 * This type of window treats determines session based on the session value
 *
 * @param session_extractor a function for retrieving event-time (in ms). Default is using processing time
 * @param inactivity time of inactivity constituting end of the session
 * @param maxsize max size for collected data. If the window length is greater then maxsize, only the last maxsize elements will be returned
 * @param inactivityInterval time interval for inactivity testing.
 *
 * Starting time for the first window is calculated based on the time of the first recieved event.
 */
case class SessionValueWindow[T](session_extractor: T ⇒ String,
                                 inactivity: FiniteDuration,
                                 maxsize: Int = 1000,
                                 inactivityInterval: FiniteDuration = 100.millisecond)
    extends GraphStage[FlowShape[(T, Committable), (Seq[T], Committable)]] {

  import TimingWindowsSupport._

  private val inlet  = Inlet[(T, Committable)]("SessionValueWindow.inlet")
  private val outlet = Outlet[(Seq[T], Committable)]("SessionValueWindow.outlet")

  override val shape: FlowShape[(T, Committable), (Seq[T], Committable)] = FlowShape(inlet, outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      private val buffer          = new ListBuffer[T]           // Data collector
      private val offsets         = new ListBuffer[Committable] // Context collector
      private var currentSession  = ""                          // Current session ID
      private var currentTs: Long = -1                          // Current ts

      // COnvert timings to miliseconds
      private val inactivityMS = inactivity.toMillis

      this.setHandlers(inlet, outlet, this)

      // Pre Start - start timer
      override def preStart(): Unit =
        scheduleWithFixedDelay(shape, 0.millisecond, inactivity)

      // Post stop - cleanup
      override def postStop(): Unit = {
        buffer.clear()
        offsets.clear()
      }

      // New event
      override def onPush(): Unit = {
        // Get element and its time
        val event   = grab(inlet)
        val session = session_extractor(event._1)
        currentTs = System.currentTimeMillis()

        // First time through - initial setup
        if (currentSession == "")
          currentSession = session

        // Session changed ?
        if (currentSession != session) {
          submitReadyWindow()
          currentSession = session
        }

        // Process current event
        updateData[T](event._1, buffer, maxsize)
        offsets += event._2

        // Get next request
        if (!hasBeenPulled(inlet)) pull(inlet)
      }

      // new window request
      override def onPull(): Unit =
        if (!hasBeenPulled(inlet)) pull(inlet)

      // New timer event
      override protected def onTimer(timerKey: Any): Unit = {
        val time = System.currentTimeMillis()
        if ((currentSession != "") && ((time - currentTs) > inactivityMS))
          submitReadyWindow()
      }

      // Support methods
      private def submitReadyWindow(): Unit = {
        val commitable = CommittableOffsetBatch(offsets.clone())
        println(s"Send committable $commitable")
        val result = (buffer.clone(), commitable)
        buffer.clear()
        offsets.clear()
        currentSession = ""
        emit(outlet, result)
      }
    }
}
