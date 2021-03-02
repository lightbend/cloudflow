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
import org.slf4j._
import cloudflow.operator.action._
import cloudflow.operator.action.runner.Runner
import io.fabric8.kubernetes.api.model.{ HasMetadata, Pod, WatchEvent }
import io.fabric8.kubernetes.client.informers.EventType

/**
 * Indicates that the status of the application has changed.
 */
case class StatusChangeEvent(appId: String, streamletName: String, watchEvent: WatchEvent) extends AppChangeEvent[Pod] {
  def namespace = watchEvent.getObject.asInstanceOf[HasMetadata].getMetadata.getNamespace
}

object StatusChangeEvent extends Event {
  lazy val log = LoggerFactory.getLogger(this.getClass)

  /** log message for when a StatusChangeEvent is identified as a status change event */
  def detected(event: StatusChangeEvent) =
    s"Status change for streamlet ${event.streamletName} detected in application ${event.appId}."

  def toStatusChangeEvent(
      currentObjects: Map[String, WatchEvent],
      watchEvent: WatchEvent): (Map[String, WatchEvent], List[StatusChangeEvent]) = {
    def getObject(we: WatchEvent) = we.getObject.asInstanceOf[Pod]

    val obj = getObject(watchEvent)
    val metadata = obj.getMetadata
    val objName = obj.getMetadata.getName
    val namespace = obj.getMetadata.getNamespace
    val absoluteName = s"$namespace.$objName"

    EventType.getByType(watchEvent.getType) match {
      case EventType.DELETION =>
        val events = (for {
          appId <- Option(metadata.getLabels.get(CloudflowLabels.AppIdLabel))
          streamletName <- Option(metadata.getLabels.get(CloudflowLabels.StreamletNameLabel))
        } yield {
          log.debug(s"[Status changes] Detected StatusChangeEvent for $absoluteName: ${changeInfo(watchEvent)}.")
          StatusChangeEvent(appId, streamletName, watchEvent)
        }).toList
        (currentObjects - absoluteName, events)

      case EventType.ADDITION | EventType.UPDATION =>
        (for {
          appId <- Option(metadata.getLabels.get(CloudflowLabels.AppIdLabel))
          streamletName <- Option(metadata.getLabels.get(CloudflowLabels.StreamletNameLabel))
        } yield {
          log.debug(s"[Status changes] Detected StatusChangeEvent for $absoluteName: ${changeInfo(watchEvent)}.")
          (currentObjects + (absoluteName -> watchEvent), List(StatusChangeEvent(appId, streamletName, watchEvent)))
        }).getOrElse((currentObjects, List()))

      case EventType.ERROR =>
        throw new Exception("Received Error event!")
    }
  }

  def toActionList(
      currentStatuses: Map[String, CloudflowApplication.Status],
      mappedApp: Option[App.Cr],
      runners: Map[String, Runner[_]],
      event: StatusChangeEvent): (Map[String, CloudflowApplication.Status], Seq[Action]) = {
    (mappedApp, event) match {
      case (Some(app), statusChangeEvent)
          if Option(app.status).flatMap(s => Option(s.appStatus)) != Some(CloudflowApplication.Status.Error) =>
        log.debug(
          s"[Status changes] Handling StatusChange for ${app.spec.appId}: ${changeInfo(statusChangeEvent.watchEvent)}.")

        val appId = app.spec.appId

        val appStatus = currentStatuses
          .get(appId)
          .map(_.updateApp(app, runners))
          .getOrElse(CloudflowApplication.Status(app.spec, runners))

        val updatedStatuses: Map[String, CloudflowApplication.Status] = statusChangeEvent match {
          case StatusChangeEvent(appId, streamletName, watchEvent) =>
            EventType.getByType(watchEvent.getType) match {
              case EventType.ADDITION | EventType.UPDATION =>
                log.debug(
                  s"[Status changes] app: $appId status of streamlet $streamletName changed: ${changeInfo(watchEvent)}")
                currentStatuses + (appId -> appStatus.updatePod(streamletName, watchEvent.getObject.asInstanceOf[Pod]))
              case EventType.DELETION =>
                log.debug(
                  s"[Status changes] app: $appId status of streamlet $streamletName changed: ${changeInfo(watchEvent)}")
                currentStatuses + (appId -> appStatus.deletePod(streamletName, watchEvent.getObject.asInstanceOf[Pod]))
              case _ =>
                log.warn(
                  s"[Status changes] Detected an unexpected change in $appId ${changeInfo(watchEvent)} in streamlet ${streamletName} (only expecting Pod changes): \n ${watchEvent}")
                currentStatuses + (appId -> appStatus)
            }
        }
        (updatedStatuses, updatedStatuses.get(appId).map(_.toAction(app)).toList)
      case (Some(app), _)
          if Option(app.status).flatMap(s => Option(s.appStatus)) == Some(CloudflowApplication.Status.Error) =>
        (currentStatuses, List())
      case (None, statusChangeEvent) => // app could not be found, remove status
        if (currentStatuses.contains(statusChangeEvent.appId)) {
          log.info(
            s"[Status changes] App could not be found for StatusChange: ${changeInfo(statusChangeEvent.watchEvent)}, removing from current statuses.")
          (currentStatuses - statusChangeEvent.appId, List())
        } else (currentStatuses, List())
    }
  }
}
