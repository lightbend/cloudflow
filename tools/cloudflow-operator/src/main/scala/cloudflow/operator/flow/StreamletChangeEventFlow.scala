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
package event

import java.util.concurrent.atomic.AtomicReference
import akka.NotUsed
import akka.datap.crd.App
import akka.kube.actions.Action
import akka.stream.scaladsl._
import cloudflow.operator.action.runner.Runner
import io.fabric8.kubernetes.api.model.Secret

object StreamletChangeEventFlow {

  import StreamletChangeEvent._

  val secretsRef = new AtomicReference(Map[String, WatchEvent[Secret]]())

  def fromWatchEvent(): Flow[WatchEvent[Secret], StreamletChangeEvent[Secret], NotUsed] =
    Flow[WatchEvent[Secret]]
      .mapConcat { watchEvent =>
        val currentObjects = secretsRef.get
        val (updatedObjects, events) = toStreamletChangeEvent(currentObjects, watchEvent)
        secretsRef.set(updatedObjects)
        events
      }

  def toConfigUpdateAction(
      runners: Map[String, Runner[_]],
      podName: String): Flow[(Option[App.Cr], StreamletChangeEvent[Secret]), Action, NotUsed] =
    Flow[(Option[App.Cr], StreamletChangeEvent[Secret])]
      .mapConcat {
        case (mappedApp, event) => toActionList(mappedApp, event, runners, podName)
      }
}
