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

import akka.stream._
import akka.stream.scaladsl._
import skuber._
import skuber.api.client._

import cloudflow.operator.action._

/**
 * Indicates that a cloudflow application was deployed or undeployed.
 */
sealed trait AppEvent {
  def app: CloudflowApplication.CR
}
case class DeployEvent(
    app: CloudflowApplication.CR,
    currentApp: Option[CloudflowApplication.CR],
    namespace: String,
    cause: ObjectResource
) extends AppEvent {
  override def toString() = s"DeployEvent for application ${app.spec.appId} in namespace $namespace"
}

case class UndeployEvent(
    app: CloudflowApplication.CR,
    namespace: String,
    cause: ObjectResource
) extends AppEvent {
  override def toString() = s"UndeployEvent for application ${app.spec.appId} in namespace $namespace"
}

object AppEvent {

  /**
   * Transforms [[skuber.api.client.WatchEvent]]s into [[AppEvent]]s.
   */
  def fromWatchEvent(logAttributes: Attributes) =
    Flow[WatchEvent[CloudflowApplication.CR]]
      .statefulMapConcat { () ⇒
        var currentApps = Map[String, WatchEvent[CloudflowApplication.CR]]()
        watchEvent ⇒ {
          val cr         = watchEvent._object
          val namespace  = cr.metadata.namespace
          val appId      = cr.spec.appId
          val currentApp = currentApps.get(appId).map(_._object)
          watchEvent._type match {
            case EventType.DELETED ⇒
              currentApps = currentApps - appId
              List(UndeployEvent(cr, namespace, watchEvent._object))
            case EventType.ADDED | EventType.MODIFIED ⇒
              if (currentApps.get(appId).forall { existingEvent ⇒
                    existingEvent._object.resourceVersion != watchEvent._object.resourceVersion &&
                    // the spec must change, otherwise it is not a deploy event (but likely a status update).
                    existingEvent._object.spec != watchEvent._object.spec
                  }) {
                currentApps = currentApps + (appId -> watchEvent)
                List(DeployEvent(cr, currentApp, namespace, watchEvent._object))
              } else List()
          }
        }
      }
      .log("app-event", AppEvent.detected)
      .withAttributes(logAttributes)

  /**
   * Transforms [[AppEvent]]s into [[Action]]s.
   */
  def toAction(implicit ctx: DeploymentContext): Flow[AppEvent, Action[ObjectResource], _] =
    Flow[AppEvent]
      .mapConcat {
        case DeployEvent(app, currentApp, namespace, cause) ⇒
          Actions.deploy(app, currentApp, namespace, cause)
        case UndeployEvent(app, namespace, cause) ⇒
          Actions.undeploy(app, namespace, cause)
      }

  def detected(appEvent: AppEvent) = s"Detected $appEvent"
}
