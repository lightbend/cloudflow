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

import scala.collection.immutable.Seq
import cloudflow.operator.action._
import io.fabric8.kubernetes.api.model.{ HasMetadata, ObjectReference, WatchEvent }
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.client.informers.EventType

/**
 * Indicates that a cloudflow application was deployed or undeployed.
 */
sealed trait AppEvent {
  def app: App.Cr
  def toActionList(runners: Map[String, runner.Runner[_]], podName: String, podNamespace: String): Seq[Action]
}

case class DeployEvent(app: App.Cr, currentApp: Option[App.Cr], cause: ObjectReference) extends AppEvent {
  override def toString() = s"DeployEvent for application ${app.spec.appId} in namespace ${app.namespace}"
  def toActionList(runners: Map[String, runner.Runner[_]], podName: String, podNamespace: String): Seq[Action] =
    Actions.deploy(app, currentApp, runners, podName, podNamespace, cause)
}

case class UndeployEvent(app: App.Cr, cause: ObjectReference) extends AppEvent {
  override def toString() = s"UndeployEvent for application ${app.spec.appId} in namespace ${app.namespace}"
  def toActionList(runners: Map[String, runner.Runner[_]], podName: String, podNamespace: String): Seq[Action] =
    Actions.undeploy(app, podName, cause)
}

/**
 * Indicates that something changed in the cloudflow application.
 */
trait AppChangeEvent[T <: HasMetadata] {
  // TODO: move to the informer?
  def watchEvent: WatchEvent
  def namespace: String
  def appId: String
}

object AppEvent {

  // TODO: move this somewhere else?
  def toObjectReference(hm: HasMetadata): ObjectReference = {
    new ObjectReference(
      hm.getApiVersion,
      "",
      hm.getKind,
      hm.getMetadata.getName,
      hm.getMetadata.getNamespace,
      hm.getMetadata.getResourceVersion,
      hm.getMetadata.getUid)
  }

  def toDeployEvent(
      currentApps: Map[String, WatchEvent],
      _watchEvent: WatchEvent): (Map[String, WatchEvent], List[AppEvent]) = {
    def getObject(we: WatchEvent) = we.getObject.asInstanceOf[App.Cr]

    val watchEventObject = getObject(_watchEvent)

    val cr = watchEventObject
    val appId = cr.spec.appId
    val currentApp = currentApps.get(appId).map(getObject)

    def hasChanged = currentApps.get(appId).forall { _existingEvent =>
      val existingEventObject = getObject(_existingEvent)

      existingEventObject.getMetadata.getResourceVersion != watchEventObject.getMetadata.getResourceVersion &&
      // the spec must change, otherwise it is not a deploy event (but likely a status update).
      existingEventObject.spec != watchEventObject.spec
    }

    EventType.getByType(_watchEvent.getType) match {
      case EventType.DELETION =>
        (currentApps - appId, List(UndeployEvent(cr, toObjectReference(watchEventObject))))
      case EventType.ADDITION | EventType.UPDATION =>
        if (hasChanged) {
          (currentApps + (appId -> _watchEvent), List(DeployEvent(cr, currentApp, toObjectReference(watchEventObject))))
        } else (currentApps, List())
      case EventType.ERROR =>
        throw new Exception("Received Error event!")
    }
  }

  def toActionList(runners: Map[String, runner.Runner[_]], podName: String, podNamespace: String)(
      appEvent: AppEvent): Seq[Action] =
    appEvent.toActionList(runners, podName, podNamespace)

  def detected(appEvent: AppEvent) = s"Detected $appEvent"
}
