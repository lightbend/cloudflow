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
import cloudflow.operator.action.runner._
import io.fabric8.kubernetes.api.model.{ HasMetadata, ObjectReference, Secret }
import io.fabric8.kubernetes.client.informers.EventType
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq

/**
 * Indicates that a streamlet has changed.
 */
case class StreamletChangeEvent[T <: HasMetadata](appId: String, streamletName: String, watchEvent: WatchEvent[T])
    extends AppChangeEvent[T] {
  def namespace = watchEvent.obj.getMetadata.getNamespace
  def absoluteStreamletKey = s"$appId.$streamletName"
}

object StreamletChangeEvent extends Event {
  private val log = LoggerFactory.getLogger(this.getClass)

  def toStreamletChangeEvent(
      currentObjects: Map[String, WatchEvent[Secret]],
      watchEvent: WatchEvent[Secret]): (Map[String, WatchEvent[Secret]], List[StreamletChangeEvent[Secret]]) = {
    val secret = watchEvent.obj
    val metadata = secret.getMetadata
    val secretName = metadata.getName
    val namespace = metadata.getNamespace
    val absoluteName = s"$namespace.$secretName"

    def hasChanged(existingEvent: WatchEvent[Secret]) = {
      secret.getMetadata.getResourceVersion != existingEvent.obj.getMetadata.getResourceVersion
    }

    watchEvent.eventType match {
      case EventType.DELETION =>
        val events = (for {
          appId <- Option(metadata.getLabels.get(CloudflowLabels.AppIdLabel))
          streamletName <- Option(metadata.getLabels.get(CloudflowLabels.StreamletNameLabel))
        } yield {
          StreamletChangeEvent[Secret](appId, streamletName, watchEvent)
        }).toList
        (currentObjects - absoluteName, events)
      case EventType.ADDITION | EventType.UPDATION =>
        if (currentObjects.get(absoluteName).forall(hasChanged)) {
          (for {
            appId <- Option(metadata.getLabels.get(CloudflowLabels.AppIdLabel))
            streamletName <- Option(metadata.getLabels.get(CloudflowLabels.StreamletNameLabel))
          } yield {
            (
              currentObjects + (absoluteName -> watchEvent),
              List(StreamletChangeEvent[Secret](appId, streamletName, watchEvent)))
          }).getOrElse((currentObjects, List()))
        } else (currentObjects, List())
      case EventType.ERROR =>
        log.error("Received an error event!")
        (Map.empty[String, WatchEvent[Secret]], List.empty[StreamletChangeEvent[Secret]])
    }
  }

  def toActionList(
      mappedApp: Option[App.Cr],
      event: StreamletChangeEvent[Secret],
      runners: Map[String, Runner[_]],
      podName: String): Seq[Action] =
    (mappedApp, event) match {
      case (Some(app), streamletChangeEvent) if streamletChangeEvent.watchEvent.eventType == EventType.UPDATION =>
        import streamletChangeEvent._
        val secret = watchEvent.obj
        val metadata = secret.getMetadata
        Option(
          metadata.getLabels
            .get(CloudflowLabels.ConfigFormat))
          .map { configFormat =>
            if (configFormat == CloudflowLabels.StreamletDeploymentConfigFormat) {
              log.debug(s"[app: ${streamletChangeEvent.appId} updating config for streamlet $streamletName]")
              actionsForRunner(app, streamletChangeEvent, podName, runners)
            } else Nil
          }
          .getOrElse(Nil)
      case _ => Nil // app could not be found, do nothing.
    }

  def actionsForRunner(
      app: App.Cr,
      streamletChangeEvent: StreamletChangeEvent[Secret],
      podName: String,
      runners: Map[String, Runner[_]]) = {
    import streamletChangeEvent._
    val secret = watchEvent.obj
    app.getSpec.deployments
      .find(_.streamletName == streamletName)
      .map { streamletDeployment =>
        log.info(s"[app: ${app.getSpec.appId} configuration changed for streamlet $streamletName]")
        val updateAction = runners
          .get(streamletDeployment.runtime)
          .map(_.streamletChangeAction(app, runners, streamletDeployment, secret))
          .toList
        val streamletChangeEventAction =
          EventActions.streamletChangeEvent(app, streamletDeployment, podName, Event.toObjectReference(secret))

        updateAction :+ streamletChangeEventAction
      }
      .getOrElse(Nil)
  }
}
