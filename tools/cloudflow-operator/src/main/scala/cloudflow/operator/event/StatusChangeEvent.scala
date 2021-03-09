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
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.informers.EventType

/**
 * Indicates that the status of the application has changed.
 */
case class StatusChangeEvent(appId: String, streamletName: String, watchEvent: WatchEvent[Pod])
    extends AppChangeEvent[Pod] {
  def namespace = watchEvent.obj.getMetadata.getNamespace
}

object StatusChangeEvent extends Event {
  lazy val log = LoggerFactory.getLogger(this.getClass)

  /** log message for when a StatusChangeEvent is identified as a status change event */
  def detected(event: StatusChangeEvent) =
    s"Status change for streamlet ${event.streamletName} detected in application ${event.appId}."

  def toStatusChangeEvent(
      currentObjects: Map[String, WatchEvent[Pod]],
      watchEvent: WatchEvent[Pod]): (Map[String, WatchEvent[Pod]], List[StatusChangeEvent]) = {
    val obj = watchEvent.obj
    val metadata = obj.getMetadata
    val objName = obj.getMetadata.getName
    val namespace = obj.getMetadata.getNamespace
    val absoluteName = s"$namespace.$objName"

    watchEvent.eventType match {
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
      currentStatuses: Map[String, App.Cr],
      mappedApp: Option[App.Cr],
      runners: Map[String, Runner[_]],
      event: StatusChangeEvent): (Map[String, App.Cr], Seq[Action]) = {
    (mappedApp, event) match {
      case (Some(app), statusChangeEvent)
          if Option(app.getStatus).flatMap(s => Option(s.appStatus)) != Some(CloudflowStatus.Status.Error) =>
        log.debug(
          s"[Status changes] Handling StatusChange for ${app.spec.appId}: ${changeInfo(statusChangeEvent.watchEvent)}.")

        val appId = app.spec.appId

        val appCr: App.Cr = currentStatuses
          .get(appId)
          .map { curr =>
            if (curr.getStatus != null) {
              app.setStatus(curr.getStatus)
              CloudflowStatus.updateApp(app, runners)
            } else {
              app.setStatus(CloudflowStatus.freshStatus(app.spec, runners))
              app
            }
          }
          .getOrElse {
            app.setStatus(CloudflowStatus.freshStatus(app.spec, runners))
            app
          }

        statusChangeEvent match {
          case StatusChangeEvent(appId, streamletName, watchEvent) =>
            watchEvent.eventType match {
              case EventType.ADDITION | EventType.UPDATION =>
                log.debug(
                  s"[Status changes] app: $appId status of streamlet $streamletName changed: ${changeInfo(watchEvent)}")
                val newStatus =
                  CloudflowStatus.updatePod(appCr.getStatus)(streamletName, watchEvent.obj)
                appCr.setStatus(newStatus)
              case EventType.DELETION =>
                log.debug(
                  s"[Status changes] app: $appId status of streamlet $streamletName changed: ${changeInfo(watchEvent)}")
                val newStatus =
                  CloudflowStatus.deletePod(appCr.getStatus)(streamletName, watchEvent.obj)
                appCr.setStatus(newStatus)
              case _ =>
                log.warn(
                  s"[Status changes] Detected an unexpected change in $appId ${changeInfo(watchEvent)} in streamlet ${streamletName} (only expecting Pod changes): \n ${watchEvent}")
            }
          case se =>
            log.warn(s"[Status changes] Unhandled status change event in $appId : $se")
        }

        val updatedStatuses: Map[String, App.Cr] = currentStatuses + (appId -> appCr)

        (updatedStatuses, updatedStatuses.get(appId).map { a => CloudflowStatus.statusUpdateAction(a)() }.toList)
      case (Some(app), _)
          if Option(app.getStatus).flatMap(s => Option(s.appStatus)) == Some(CloudflowStatus.Status.Error) =>
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
