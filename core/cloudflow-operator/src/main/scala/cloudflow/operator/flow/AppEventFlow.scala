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

import akka.stream._
import akka.stream.scaladsl._
import skuber._
import skuber.api.client._
import cloudflow.operator.action._
import cloudflow.operator.event._

object AppEventFlow {

  /**
   * Transforms [[skuber.api.client.WatchEvent]]s into [[AppEvent]]s.
   */
  def fromWatchEvent(logAttributes: Attributes) =
    Flow[WatchEvent[CloudflowApplication.CR]]
      .statefulMapConcat { () ⇒
        // TODO make this available in other streams directly through an Actor
        // to prevent possibility of getting an out of sync CR from the cluster in mapToAppInSameNamespace (which might be a small chance).
        var currentApps = Map[String, WatchEvent[CloudflowApplication.CR]]()
        watchEvent ⇒ {
          val (updatedApps, events) = AppEvent.toDeployEvent(currentApps, watchEvent)
          currentApps = updatedApps
          events
        }
      }
      .log("app-event", AppEvent.detected)
      .withAttributes(logAttributes)

  /**
   * Transforms [[AppEvent]]s into [[Action]]s.
   */
  def toAction(implicit ctx: DeploymentContext): Flow[AppEvent, Action[ObjectResource], _] =
    Flow[AppEvent]
      .mapConcat(AppEvent.toActionList)
}
