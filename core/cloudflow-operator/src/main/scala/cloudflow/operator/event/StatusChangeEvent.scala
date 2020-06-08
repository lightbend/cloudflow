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

import scala.concurrent._

import akka.actor._
import akka.NotUsed
import akka.stream.scaladsl._
import skuber._
import skuber.apps.v1.Deployment
import skuber.api.client._
import skuber.json.format._

import cloudflow.operator.action._
import cloudflow.operator.runner._
import cloudflow.operator.runner.SparkResource.SpecPatch

/**
 * Indicates that the status of the application has changed.
 */
case class StatusChangeEvent(appId: String, streamletName: String, watchEvent: WatchEvent[Pod]) extends AppChangeEvent[Pod] {
  def namespace = watchEvent._object.metadata.namespace
}

object StatusChangeEvent extends Event {

  /** log message for when a StreamletChangeEvent is identified as a status change event */
  def detected(event: StatusChangeEvent) =
    s"Status change for streamlet ${event.streamletName} detected in application ${event.appId}."

  /**
   * Transforms [[skuber.api.client.WatchEvent]]s into [[StatusChangeEvent]]s.
   * Only watch events for resources that have been created by the cloudflow operator are turned into [[StatusChangeEvent]]s.
   */
  def fromWatchEvent(): Flow[WatchEvent[Pod], StatusChangeEvent, NotUsed] =
    Flow[WatchEvent[Pod]]
      .statefulMapConcat { () ⇒
        var currentObjects = Map[String, WatchEvent[Pod]]()
        watchEvent ⇒ {
          val obj          = watchEvent._object
          val metadata     = obj.metadata
          val objName      = obj.metadata.name
          val namespace    = obj.metadata.namespace
          val absoluteName = s"$namespace.$objName"

          watchEvent._type match {
            case EventType.DELETED ⇒
              currentObjects = currentObjects - absoluteName
              (for {
                appId         ← metadata.labels.get(Operator.AppIdLabel)
                streamletName ← metadata.labels.get(Operator.StreamletNameLabel)
              } yield {
                StatusChangeEvent(appId, streamletName, watchEvent)
              }).toList

            case EventType.ADDED | EventType.MODIFIED ⇒
              (for {
                appId         ← metadata.labels.get(Operator.AppIdLabel)
                streamletName ← metadata.labels.get(Operator.StreamletNameLabel)
              } yield {
                currentObjects = currentObjects + (absoluteName -> watchEvent)
                StatusChangeEvent(appId, streamletName, watchEvent)
              }).toList
          }
        }
      }

  def toStatusUpdateAction(
      implicit system: ActorSystem
  ): Flow[(Option[CloudflowApplication.CR], StatusChangeEvent), Action[ObjectResource], NotUsed] =
    Flow[(Option[CloudflowApplication.CR], StatusChangeEvent)]
      .statefulMapConcat { () ⇒
        var currentStatuses = Map[String, CloudflowApplication.Status]()

        {
          case (Some(app), streamletChangeEvent) ⇒
            val appId = app.spec.appId

            val appStatus = currentStatuses
              .get(appId)
              .map(_.updateApp(app))
              .getOrElse(CloudflowApplication.Status(app.spec))

            streamletChangeEvent match {
              case StatusChangeEvent(appId, streamletName, watchEvent) ⇒
                watchEvent match {
                  case WatchEvent(EventType.ADDED | EventType.MODIFIED, pod: Pod) ⇒
                    system.log.info(s"[app: $appId status of streamlet $streamletName changed: ${changeInfo(watchEvent)}")
                    currentStatuses = currentStatuses + (appId -> appStatus.updatePod(streamletName, pod))
                  case WatchEvent(EventType.DELETED, pod: Pod) ⇒
                    system.log.info(s"[app: $appId status of streamlet $streamletName changed: ${changeInfo(watchEvent)}")
                    currentStatuses = currentStatuses + (appId -> appStatus.deletePod(streamletName, pod))
                  case _ ⇒
                    system.log.warning(
                      s"Detected an unexpected change in $appId ${changeInfo(watchEvent)} in streamlet ${streamletName} (only expecting Pod changes): \n ${watchEvent}"
                    )
                }
            }
            currentStatuses.get(appId).map(_.toAction(app)).toList
          case (None, streamletChangeEvent) ⇒ // app could not be found, remove status
            currentStatuses = currentStatuses - streamletChangeEvent.appId
            List()
        }
      }
}
