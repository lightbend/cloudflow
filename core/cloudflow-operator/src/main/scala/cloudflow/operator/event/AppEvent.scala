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

import akka.datap.crd.App
import akka.kube.actions.Action
import cloudflow.operator.action._
import io.fabric8.kubernetes.api.model.{ HasMetadata, ObjectReference }
import io.fabric8.kubernetes.client.informers.EventType
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq

/**
 * Indicates that a cloudflow application was deployed or undeployed.
 */
sealed trait AppEvent {
  def app: App.Cr
  def toActionList(runners: Map[String, runner.Runner[_]], podName: String, podNamespace: String): Seq[Action]
}

case class DeployEvent(app: App.Cr, currentApp: Option[App.Cr], cause: ObjectReference) extends AppEvent {
  override def toString() = s"DeployEvent for application ${app.getSpec.appId} in namespace ${app.namespace}"
  def toActionList(runners: Map[String, runner.Runner[_]], podName: String, podNamespace: String): Seq[Action] =
    Actions.deploy(app, currentApp, runners, podName, podNamespace, cause)
}

case class UndeployEvent(app: App.Cr, cause: ObjectReference) extends AppEvent {
  override def toString() = s"UndeployEvent for application ${app.getSpec.appId} in namespace ${app.namespace}"
  def toActionList(runners: Map[String, runner.Runner[_]], podName: String, podNamespace: String): Seq[Action] =
    Actions.undeploy(app, podName, cause)
}

/**
 * Indicates that something changed in the cloudflow application.
 */
trait AppChangeEvent[T <: HasMetadata] {
  def watchEvent: WatchEvent[T]
  def namespace: String
  def appId: String
}

object AppEvent {
  private val log = LoggerFactory.getLogger(this.getClass)

  def toDeployEvent(
      currentApps: Map[String, WatchEvent[App.Cr]],
      watchEvent: WatchEvent[App.Cr]): (Map[String, WatchEvent[App.Cr]], List[AppEvent]) = {
    val cr = watchEvent.obj
    val appId = cr.getSpec.appId
    val currentApp = currentApps.get(appId).map(_.obj)

    def hasChanged = currentApps.get(appId).forall { _existingEvent =>
      val existingEventObject = _existingEvent.obj

      existingEventObject.getMetadata.getResourceVersion != watchEvent.obj.getMetadata.getResourceVersion &&
      // the spec must change, otherwise it is not a deploy event (but likely a status update).
      existingEventObject.getSpec != watchEvent.obj.getSpec
    }

    watchEvent.eventType match {
      case EventType.DELETION =>
        (currentApps - appId, List(UndeployEvent(cr, Event.toObjectReference(watchEvent.obj))))
      case EventType.ADDITION | EventType.UPDATION =>
        if (hasChanged) {
          (
            currentApps + (appId -> watchEvent),
            List(DeployEvent(cr, currentApp, Event.toObjectReference(watchEvent.obj))))
        } else {
          (currentApps, List())
        }
      case EventType.ERROR =>
        log.error("Received an error event!")
        (Map.empty[String, WatchEvent[App.Cr]], List.empty[AppEvent])
    }
  }

  def toActionList(runners: Map[String, runner.Runner[_]], podName: String, podNamespace: String)(
      appEvent: AppEvent): Seq[Action] =
    appEvent.toActionList(runners, podName, podNamespace)

  def detected(appEvent: AppEvent) = s"Detected $appEvent"
}
