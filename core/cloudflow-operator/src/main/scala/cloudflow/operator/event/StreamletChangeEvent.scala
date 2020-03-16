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
import skuber.api.client._

import cloudflow.operator.action._
import cloudflow.operator.runner._
import cloudflow.operator.runner.SparkResource.SpecPatch

/**
 * Indicates that a streamlet has changed.
 */
case class StreamletChangeEvent[T <: ObjectResource](appId: String, streamletName: String, namespace: String, watchEvent: WatchEvent[T])

object ConfigChangeEvent {

  /** log message for when a StreamletChangeEvent is identified as a configuration change event */
  def detected[T <: ObjectResource](event: StreamletChangeEvent[T]) =
    s"Configuration change for streamlet ${event.streamletName} detected in application ${event.appId}."
}

object StatusChangeEvent {

  /** log message for when a StreamletChangeEvent is identified as a status change event */
  def detected[T <: ObjectResource](event: StreamletChangeEvent[T]) =
    s"Status change for streamlet ${event.streamletName} detected in application ${event.appId}."
}

object StreamletChangeEvent {

  /**
   * Transforms [[skuber.api.client.WatchEvent]]s into [[StreamletChangeEvent]]s.
   * Only watch events for resources that have been created by the cloudflow operator are turned into [[StreamletChangeEvent]]s.
   * (watch events are filtered by Operator.AppIdLabel and Operator.StreamletNameLabel)
   */
  def fromWatchEvent[O <: ObjectResource](
      modifiedOnly: Boolean = false
  )(implicit system: ActorSystem): Flow[WatchEvent[O], StreamletChangeEvent[O], NotUsed] =
    Flow[WatchEvent[O]]
      .filter(_._type == EventType.MODIFIED || !modifiedOnly)
      .mapConcat { watchEvent ⇒
        val obj       = watchEvent._object
        val metadata  = obj.metadata
        val namespace = obj.metadata.namespace

        (for {
          appId         ← metadata.labels.get(Operator.AppIdLabel)
          streamletName ← metadata.labels.get(Operator.StreamletNameLabel)
          _ = system.log.info(s"[app: $appId streamlet: $streamletName] streamlet changed ${changeInfo(watchEvent)}")
        } yield {
          StreamletChangeEvent(appId, streamletName, namespace, watchEvent)
        }).toList
      }

  private def changeInfo[T <: ObjectResource](watchEvent: WatchEvent[T]) = {
    val obj      = watchEvent._object
    val metadata = obj.metadata
    s"(${getKind(obj)} ${metadata.name} ${watchEvent._type})"
  }

  private def getKind(obj: ObjectResource) = if (obj.kind.isEmpty) obj.getClass.getSimpleName else obj.kind // sometimes kind is empty.

  /**
   * Finds the associated [[CloudflowApplication.CR]]s for [[StreamletChangeEvent]]s.
   * The resulting flow outputs tuples of the app and the streamlet change event.
   */
  def mapToAppInSameNamespace[O <: ObjectResource](
      client: KubernetesClient
  )(implicit ec: ExecutionContext): Flow[StreamletChangeEvent[O], (Option[CloudflowApplication.CR], StreamletChangeEvent[O]), NotUsed] =
    Flow[StreamletChangeEvent[O]].mapAsync(1) { streamletChangeEvent ⇒
      val ns = streamletChangeEvent.watchEvent._object.metadata.namespace
      // toAppChangeEvent
      client.usingNamespace(ns).getOption[CloudflowApplication.CR](streamletChangeEvent.appId).map {
        case a @ Some(_) ⇒ a    -> streamletChangeEvent
        case none        ⇒ none -> streamletChangeEvent
      }
    }

  def toStatusUpdateAction[O <: ObjectResource](
      implicit system: ActorSystem
  ): Flow[(Option[CloudflowApplication.CR], StreamletChangeEvent[O]), Action[ObjectResource], NotUsed] =
    Flow[(Option[CloudflowApplication.CR], StreamletChangeEvent[O])]
      .statefulMapConcat { () ⇒
        var currentStatuses = Map[String, CloudflowApplication.Status]()

        {
          case (Some(app), streamletChangeEvent) ⇒
            val appId = app.spec.appId
            val appStatus = currentStatuses
              .get(appId)
              .map(_.updateSpec(app.spec))
              .getOrElse(CloudflowApplication.Status(app))

            streamletChangeEvent match {
              case StreamletChangeEvent(appId, streamletName, _, watchEvent) ⇒
                watchEvent match {
                  case WatchEvent(EventType.ADDED | EventType.MODIFIED, pod: Pod) ⇒
                    currentStatuses = currentStatuses + (appId -> appStatus.updatePod(streamletName, pod))
                  case WatchEvent(EventType.DELETED, pod: Pod) ⇒
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

  def toConfigUpdateAction[O <: ObjectResource](
      implicit system: ActorSystem,
      ctx: DeploymentContext
  ): Flow[(Option[CloudflowApplication.CR], StreamletChangeEvent[O]), Action[ObjectResource], NotUsed] =
    Flow[(Option[CloudflowApplication.CR], StreamletChangeEvent[O])]
      .map {
        case (Some(app), streamletChangeEvent) ⇒
          import streamletChangeEvent._
          app.spec.deployments
            .find(_.streamletName == streamletName)
            .map { streamletDeployment ⇒
              system.log.info(s"[app: $appId streamlet: $streamletName] for runtime ${streamletDeployment.runtime} configuration changed.")
              val updateLabels = Map(Operator.ConfigUpdateLabel -> System.currentTimeMillis.toString)
              val updateAction = streamletDeployment.runtime match {
                case AkkaRunner.runtime ⇒
                  val resource        = AkkaRunner.resource(streamletDeployment, app, app.metadata.namespace, updateLabels)
                  val labeledResource = resource.copy(metadata = resource.metadata.copy(labels = resource.metadata.labels ++ updateLabels))
                  Action.update(labeledResource, runner.AkkaRunner.editor)

                case SparkRunner.runtime ⇒
                  val resource        = SparkRunner.resource(streamletDeployment, app, app.metadata.namespace, updateLabels)
                  val labeledResource = resource.copy(metadata = resource.metadata.copy(labels = resource.metadata.labels ++ updateLabels))
                  val patch           = SpecPatch(labeledResource.spec)
                  Action.patch(resource, patch)(SparkRunner.format, SparkRunner.patchFormat, SparkRunner.resourceDefinition)
                case FlinkRunner.runtime ⇒
                  val resource        = FlinkRunner.resource(streamletDeployment, app, app.metadata.namespace, updateLabels)
                  val labeledResource = resource.copy(metadata = resource.metadata.copy(labels = resource.metadata.labels ++ updateLabels))
                  Action.update(labeledResource, runner.FlinkRunner.editor)
              }
              val streamletChangeEventAction =
                EventActions.streamletChangeEvent(app, streamletDeployment, namespace, watchEvent._object)

              List(updateAction, streamletChangeEventAction)
            }
            .getOrElse(Nil)
        case _ ⇒ Nil // app could not be found, do nothing.
      }
      .mapConcat(_.toList)
}
