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

import java.util.concurrent.atomic.AtomicReference
import akka.NotUsed
import akka.datap.crd.App
import akka.kube.actions.Action
import akka.stream.scaladsl._
import cloudflow.operator.action.CloudflowApplication
import org.slf4j._
import cloudflow.operator.action.runner.Runner
import cloudflow.operator.event._
import io.fabric8.kubernetes.api.model.WatchEvent

object StatusChangeEventFlow extends {
  import StatusChangeEvent._

  lazy val log = LoggerFactory.getLogger(this.getClass)

  val podsRef = new AtomicReference(Map[String, WatchEvent]())
  val statusRef = new AtomicReference(Map[String, CloudflowApplication.Status]())

  def fromWatchEvent(): Flow[WatchEvent, StatusChangeEvent, NotUsed] =
    Flow[WatchEvent]
      .mapConcat { watchEvent =>
        val currentObjects = podsRef.get
        val (updatedObjects, events) = toStatusChangeEvent(currentObjects, watchEvent)
        podsRef.set(updatedObjects)
        events
      }

  def toStatusUpdateAction(
      runners: Map[String, Runner[_]]): Flow[(Option[App.Cr], StatusChangeEvent), Action, NotUsed] =
    Flow[(Option[App.Cr], StatusChangeEvent)]
      .mapConcat {
        case (mappedApp, event) =>
          val currentStatuses = statusRef.get
          val (updatedStatuses, actionList) = toActionList(currentStatuses, mappedApp, runners, event)
          statusRef.set(updatedStatuses)
          actionList
      }
}
