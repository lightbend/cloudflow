/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.operator
package flow

import akka.datap.crd.App
import akka.kube.actions.Action
import akka.stream._
import akka.stream.scaladsl._
import cloudflow.operator.action.runner.Runner
import cloudflow.operator.event._

import java.util.concurrent.atomic.AtomicReference

object AppEventFlow {
  // keeps state of apps across stream restarts
  val appsRef = new AtomicReference(Map[String, WatchEvent[App.Cr]]())

  def fromWatchEvent(logAttributes: Attributes) =
    Flow[WatchEvent[App.Cr]]
      .mapConcat { watchEvent =>
        val currentApps = appsRef.get
        val (updatedApps, events) = AppEvent.toDeployEvent(currentApps, watchEvent)
        appsRef.set(updatedApps)
        events
      }
      .log("app-event", AppEvent.detected)
      .withAttributes(logAttributes)

  /**
   * Transforms [[AppEvent]]s into [[Action]]s.
   */
  def toAction(runners: Map[String, Runner[_]], podName: String, podNamespace: String): Flow[AppEvent, Action, _] =
    Flow[AppEvent]
      .mapConcat(_.toActionList(runners, podName, podNamespace))
}
