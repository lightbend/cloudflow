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
import org.slf4j._

import skuber.Pod
import skuber.api.client.{ EventType, WatchEvent }

import cloudflow.operator.action._
import cloudflow.operator.action.runner.Runner

/**
 * Indicates that the status of the application has changed.
 */
case class StatusChangeEvent(appId: String, streamletName: String, watchEvent: WatchEvent[Pod]) extends AppChangeEvent[Pod] {
  def namespace = watchEvent._object.metadata.namespace
}

object StatusChangeEvent extends Event {
  lazy val log = LoggerFactory.getLogger(this.getClass)

  /** log message for when a StatusChangeEvent is identified as a status change event */
  def detected(event: StatusChangeEvent) =
    s"Status change for streamlet ${event.streamletName} detected in application ${event.appId}."

  def toStatusChangeEvent(currentObjects: Map[String, WatchEvent[Pod]],
                          watchEvent: WatchEvent[Pod]): (Map[String, WatchEvent[Pod]], List[StatusChangeEvent]) = {
    val obj          = watchEvent._object
    val metadata     = obj.metadata
    val objName      = obj.metadata.name
    val namespace    = obj.metadata.namespace
    val absoluteName = s"$namespace.$objName"

    watchEvent._type match {
      case EventType.DELETED =>
        val events = (for {
          appId         ← metadata.labels.get(CloudflowLabels.AppIdLabel)
          streamletName ← metadata.labels.get(CloudflowLabels.StreamletNameLabel)
        } yield {
          log.info(s"[Status changes] Detected StatusChangeEvent for $absoluteName: ${changeInfo(watchEvent)}.")
          StatusChangeEvent(appId, streamletName, watchEvent)
        }).toList
        (currentObjects - absoluteName, events)

      case EventType.ADDED | EventType.MODIFIED =>
        (for {
          appId         ← metadata.labels.get(CloudflowLabels.AppIdLabel)
          streamletName ← metadata.labels.get(CloudflowLabels.StreamletNameLabel)
        } yield {
          log.info(s"[Status changes] Detected StatusChangeEvent for $absoluteName: ${changeInfo(watchEvent)}.")
          (currentObjects + (absoluteName -> watchEvent), List(StatusChangeEvent(appId, streamletName, watchEvent)))
        }).getOrElse((currentObjects, List()))
    }
  }

  def toActionList(currentStatuses: Map[String, CloudflowApplication.Status],
                   mappedApp: Option[CloudflowApplication.CR],
                   runners: Map[String, Runner[_]],
                   event: StatusChangeEvent): (Map[String, CloudflowApplication.Status], Seq[Action]) =
    (mappedApp, event) match {
      case (Some(app), statusChangeEvent) if app.status.flatMap(_.appStatus) != Some(CloudflowApplication.Status.Error) =>
        log.info(s"[Status changes] Handling StatusChange for ${app.spec.appId}: ${changeInfo(statusChangeEvent.watchEvent)}.")

        val appId = app.spec.appId

        val appStatus = currentStatuses
          .get(appId)
          .map(_.updateApp(app, runners))
          .getOrElse(CloudflowApplication.Status(app.spec, runners))

        val updatedStatuses = statusChangeEvent match {
          case StatusChangeEvent(appId, streamletName, watchEvent) =>
            watchEvent match {
              case WatchEvent(EventType.ADDED | EventType.MODIFIED, pod: Pod) =>
                log.info(s"[Status changes] app: $appId status of streamlet $streamletName changed: ${changeInfo(watchEvent)}")
                currentStatuses + (appId -> appStatus.updatePod(streamletName, pod))
              case WatchEvent(EventType.DELETED, pod: Pod) =>
                log.info(s"[Status changes] app: $appId status of streamlet $streamletName changed: ${changeInfo(watchEvent)}")
                currentStatuses + (appId -> appStatus.deletePod(streamletName, pod))
              case _ =>
                log.warn(
                  s"[Status changes] Detected an unexpected change in $appId ${changeInfo(watchEvent)} in streamlet ${streamletName} (only expecting Pod changes): \n ${watchEvent}"
                )
                currentStatuses + (appId -> appStatus)
            }
        }
        (updatedStatuses, updatedStatuses.get(appId).map(_.toAction(app)).toList)
      case (Some(app), _) if app.status.flatMap(_.appStatus) == Some(CloudflowApplication.Status.Error) =>
        (currentStatuses, List())
      case (None, statusChangeEvent) => // app could not be found, remove status
        log.info(
          s"[Status changes] App could not be found for StatusChange: ${changeInfo(statusChangeEvent.watchEvent)}, removing from current statuses."
        )
        (currentStatuses - statusChangeEvent.appId, List())
    }

}
