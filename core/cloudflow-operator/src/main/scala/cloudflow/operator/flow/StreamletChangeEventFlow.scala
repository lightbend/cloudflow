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
package event

import akka.NotUsed
import akka.stream.scaladsl._

import skuber._
import skuber.api.client._

import cloudflow.operator.action._

object StreamletChangeEventFlow {

  import StreamletChangeEvent._

  /**
   * Transforms [[skuber.api.client.WatchEvent]]s into [[StreamletChangeEvent]]s.
   * Only watch events that have changed for resources that have been created by the cloudflow operator are turned into [[StreamletChangeEvent]]s.
   */
  def fromWatchEvent(): Flow[WatchEvent[Secret], StreamletChangeEvent[Secret], NotUsed] =
    Flow[WatchEvent[Secret]]
      .statefulMapConcat { () =>
        var currentObjects = Map[String, WatchEvent[Secret]]()
        watchEvent => {
          val (updatedObjects, events) = toStreamletChangeEvent(currentObjects, watchEvent)
          currentObjects = updatedObjects
          events
        }
      }

  def toConfigUpdateAction(
      implicit ctx: DeploymentContext
  ): Flow[(Option[CloudflowApplication.CR], StreamletChangeEvent[Secret]), Action, NotUsed] =
    Flow[(Option[CloudflowApplication.CR], StreamletChangeEvent[Secret])]
      .mapConcat {
        case (mappedApp, event) => toActionList(mappedApp, event)
      }
}
