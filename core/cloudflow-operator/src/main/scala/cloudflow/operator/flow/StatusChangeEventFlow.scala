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

package cloudflow.operator
package flow

import akka.NotUsed
import akka.stream.scaladsl._
import org.slf4j._

import skuber._
import skuber.api.client._

import cloudflow.operator.action._
import cloudflow.operator.action.runner.Runner
import cloudflow.operator.event._

object StatusChangeEventFlow extends {
  import StatusChangeEvent._

  lazy val log = LoggerFactory.getLogger(this.getClass)

  /**
   * Transforms [[skuber.api.client.WatchEvent]]s into [[StatusChangeEvent]]s.
   * Only watch events for resources that have been created by the cloudflow operator are turned into [[StatusChangeEvent]]s.
   */
  def fromWatchEvent(): Flow[WatchEvent[Pod], StatusChangeEvent, NotUsed] =
    Flow[WatchEvent[Pod]]
      .statefulMapConcat { () ⇒
        var currentObjects = Map[String, WatchEvent[Pod]]()
        watchEvent ⇒ {
          val (updatedObjects, events) = toStatusChangeEvent(currentObjects, watchEvent)
          currentObjects = updatedObjects
          events
        }
      }

  def toStatusUpdateAction(runners: Map[String, Runner[_]]): Flow[(Option[CloudflowApplication.CR], StatusChangeEvent), Action, NotUsed] =
    Flow[(Option[CloudflowApplication.CR], StatusChangeEvent)]
      .statefulMapConcat { () ⇒
        var currentStatuses = Map[String, CloudflowApplication.Status]()

        {
          case (mappedApp, event) =>
            val (updatedStatuses, actionList) = toActionList(currentStatuses, mappedApp, runners, event)
            currentStatuses = updatedStatuses
            actionList
        }
      }
}
