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

import org.slf4j.LoggerFactory

import skuber.{ ObjectResource, Secret }
import skuber.api.client.{ EventType, WatchEvent }

import cloudflow.operator.action._
import cloudflow.operator.action.runner._

/**
 * Indicates that a streamlet has changed.
 */
case class StreamletChangeEvent[T <: ObjectResource](appId: String, streamletName: String, watchEvent: WatchEvent[T])
    extends AppChangeEvent[T] {
  def namespace            = watchEvent._object.metadata.namespace
  def absoluteStreamletKey = s"$appId.$streamletName"
}

object StreamletChangeEvent extends Event {
  private val log = LoggerFactory.getLogger(this.getClass)

  def toStreamletChangeEvent(currentObjects: Map[String, WatchEvent[Secret]],
                             watchEvent: WatchEvent[Secret]): (Map[String, WatchEvent[Secret]], List[StreamletChangeEvent[Secret]]) = {
    val secret       = watchEvent._object
    val metadata     = secret.metadata
    val secretName   = secret.metadata.name
    val namespace    = secret.metadata.namespace
    val absoluteName = s"$namespace.$secretName"

    def hasChanged(existingEvent: WatchEvent[Secret]) =
      watchEvent._object.resourceVersion != existingEvent._object.resourceVersion

    watchEvent._type match {
      case EventType.DELETED ⇒
        val events = (for {
          appId         ← metadata.labels.get(CloudflowLabels.AppIdLabel)
          streamletName ← metadata.labels.get(CloudflowLabels.StreamletNameLabel)
        } yield {
          StreamletChangeEvent(appId, streamletName, watchEvent)
        }).toList
        (currentObjects - absoluteName, events)
      case EventType.ADDED | EventType.MODIFIED ⇒
        if (currentObjects.get(absoluteName).forall(hasChanged)) {
          (for {
            appId         ← metadata.labels.get(CloudflowLabels.AppIdLabel)
            streamletName ← metadata.labels.get(CloudflowLabels.StreamletNameLabel)
          } yield {
            (currentObjects + (absoluteName -> watchEvent), List(StreamletChangeEvent(appId, streamletName, watchEvent)))
          }).getOrElse((currentObjects, List()))
        } else (currentObjects, List())
    }
  }

  def toActionList(mappedApp: Option[CloudflowApplication.CR],
                   event: StreamletChangeEvent[Secret],
                   runners: Map[String, Runner[_]],
                   podName: String): Seq[Action] =
    (mappedApp, event) match {
      case (Some(app), streamletChangeEvent) if streamletChangeEvent.watchEvent._type == EventType.MODIFIED ⇒
        import streamletChangeEvent._
        val secret   = watchEvent._object
        val metadata = secret.metadata
        metadata.labels
          .get(CloudflowLabels.ConfigFormat)
          .map { configFormat =>
            if (configFormat == CloudflowLabels.StreamletDeploymentConfigFormat) {
              log.debug(s"[app: ${streamletChangeEvent.appId} updating config for streamlet $streamletName]")
              actionsForRunner(
                app,
                streamletChangeEvent,
                podName,
                runners
              )
            } else Nil
          }
          .getOrElse(Nil)
      case _ ⇒ Nil // app could not be found, do nothing.
    }

  def actionsForRunner(app: CloudflowApplication.CR,
                       streamletChangeEvent: StreamletChangeEvent[Secret],
                       podName: String,
                       runners: Map[String, Runner[_]]) = {
    import streamletChangeEvent._
    app.spec.deployments
      .find(_.streamletName == streamletName)
      .map { streamletDeployment ⇒
        log.info(s"[app: ${app.spec.appId} configuration changed for streamlet $streamletName]")
        val updateAction = runners.get(streamletDeployment.runtime).map(_.streamletChangeAction(app, runners, streamletDeployment)).toList
        val streamletChangeEventAction =
          EventActions.streamletChangeEvent(app, streamletDeployment, namespace, podName, watchEvent._object)

        updateAction :+ streamletChangeEventAction
      }
      .getOrElse(Nil)
  }
}
