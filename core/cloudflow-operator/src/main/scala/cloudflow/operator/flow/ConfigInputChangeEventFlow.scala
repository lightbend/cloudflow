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
import skuber._
import skuber.api.client._
import cloudflow.operator.action._
import cloudflow.operator.event._

object ConfigInputChangeEventFlow {
  import ConfigInputChangeEvent._

  /**
   * Transforms [[skuber.api.client.WatchEvent]]s into [[ConfigInputChangeEvent]]s.
   */
  def fromWatchEvent(): Flow[WatchEvent[Secret], ConfigInputChangeEvent, NotUsed] =
    Flow[WatchEvent[Secret]]
      .statefulMapConcat { () ⇒
        var currentSecrets = Map[String, WatchEvent[Secret]]()

        watchEvent ⇒ {
          val (updatedSecrets, events) = toConfigInputChangeEvent(currentSecrets, watchEvent)
          currentSecrets = updatedSecrets
          events
        }
      }

  def toInputConfigUpdateAction(
      podNamespace: String
  ): Flow[(Option[CloudflowApplication.CR], ConfigInputChangeEvent), Action, NotUsed] =
    Flow[(Option[CloudflowApplication.CR], ConfigInputChangeEvent)]
      .map {
        case (mappedApp, event) => toActionList(mappedApp, event, podNamespace)
      }
      .mapConcat(_.toList)
}
