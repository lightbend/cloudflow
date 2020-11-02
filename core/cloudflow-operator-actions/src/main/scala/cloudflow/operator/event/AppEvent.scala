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

import scala.collection.immutable.Seq

import skuber.ObjectResource
import skuber.api.client.{ EventType, WatchEvent }

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

/**
 * Indicates that something changed in the cloudflow application.
 */
trait AppChangeEvent[T <: ObjectResource] {
  def watchEvent: WatchEvent[T]
  def namespace: String
  def appId: String
}

object AppEvent {
  def toDeployEvent(
      currentApps: Map[String, WatchEvent[CloudflowApplication.CR]],
      watchEvent: WatchEvent[CloudflowApplication.CR]
  ): (Map[String, WatchEvent[CloudflowApplication.CR]], List[AppEvent]) = {
    val cr         = watchEvent._object
    val namespace  = cr.metadata.namespace
    val appId      = cr.spec.appId
    val currentApp = currentApps.get(appId).map(_._object)
    watchEvent._type match {
      case EventType.DELETED ⇒
        (currentApps - appId, List(UndeployEvent(cr, namespace, watchEvent._object)))
      case EventType.ADDED | EventType.MODIFIED ⇒
        if (currentApps.get(appId).forall { existingEvent ⇒
              existingEvent._object.resourceVersion != watchEvent._object.resourceVersion &&
              // the spec must change, otherwise it is not a deploy event (but likely a status update).
              existingEvent._object.spec != watchEvent._object.spec
            }) {
          (currentApps + (appId -> watchEvent), List(DeployEvent(cr, currentApp, namespace, watchEvent._object)))
        } else (currentApps, List())
    }
  }

  def toActionList(runners: Map[String, runner.Runner[_]], podName: String, podNamespace: String)(appEvent: AppEvent): Seq[Action] =
    appEvent match {
      case DeployEvent(app, currentApp, namespace, cause) ⇒
        Actions.deploy(app, currentApp, runners, namespace, podName, podNamespace, cause)
      case UndeployEvent(app, namespace, cause) ⇒
        Actions.undeploy(app, namespace, podName, cause)
    }

  def detected(appEvent: AppEvent) = s"Detected $appEvent"
}
