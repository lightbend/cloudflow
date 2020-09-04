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
 * Indicates that a streamlet has changed.
 */
case class StreamletChangeEvent[T <: ObjectResource](appId: String, streamletName: String, watchEvent: WatchEvent[T])
    extends AppChangeEvent[T] {
  def namespace            = watchEvent._object.metadata.namespace
  def absoluteStreamletKey = s"$appId.$streamletName"
}

object StreamletChangeEvent extends Event {

  /**
   * Transforms [[skuber.api.client.WatchEvent]]s into [[StreamletChangeEvent]]s.
   * Only watch events that have changed for resources that have been created by the cloudflow operator are turned into [[StreamletChangeEvent]]s.
   */
  def fromWatchEvent[O <: ObjectResource](): Flow[WatchEvent[O], StreamletChangeEvent[O], NotUsed] =
    Flow[WatchEvent[O]]
      .statefulMapConcat { () ⇒
        var currentObjects = Map[String, WatchEvent[O]]()
        watchEvent ⇒ {
          val secret       = watchEvent._object
          val metadata     = secret.metadata
          val secretName   = secret.metadata.name
          val namespace    = secret.metadata.namespace
          val absoluteName = s"$namespace.$secretName"

          def hasChanged(existingEvent: WatchEvent[O]) =
            watchEvent._object.resourceVersion != existingEvent._object.resourceVersion

          watchEvent._type match {
            case EventType.DELETED ⇒
              currentObjects = currentObjects - absoluteName
              (for {
                appId         ← metadata.labels.get(Operator.AppIdLabel)
                streamletName ← metadata.labels.get(Operator.StreamletNameLabel)
              } yield {
                StreamletChangeEvent(appId, streamletName, watchEvent)
              }).toList

            case EventType.ADDED | EventType.MODIFIED ⇒
              if (currentObjects.get(absoluteName).forall(hasChanged)) {
                (for {
                  appId         ← metadata.labels.get(Operator.AppIdLabel)
                  streamletName ← metadata.labels.get(Operator.StreamletNameLabel)
                } yield {
                  currentObjects = currentObjects + (absoluteName -> watchEvent)
                  StreamletChangeEvent(appId, streamletName, watchEvent)
                }).toList
              } else List()
          }
        }
      }

  def toConfigUpdateAction(
      implicit system: ActorSystem,
      ctx: DeploymentContext
  ): Flow[(Option[CloudflowApplication.CR], StreamletChangeEvent[Secret]), Action[ObjectResource], NotUsed] =
    Flow[(Option[CloudflowApplication.CR], StreamletChangeEvent[Secret])]
      .mapConcat {
        case (Some(app), streamletChangeEvent) if streamletChangeEvent.watchEvent._type == EventType.MODIFIED ⇒
          import streamletChangeEvent._
          val secret   = watchEvent._object
          val metadata = secret.metadata
          metadata.labels
            .get(CloudflowLabels.ConfigFormat)
            .map { configFormat =>
              if (configFormat == CloudflowLabels.StreamletDeploymentConfigFormat) {
                system.log.debug(s"[app: ${streamletChangeEvent.appId} updating config for streamlet $streamletName]")
                actionsForRunner(
                  app,
                  streamletChangeEvent
                )
              } else Nil
            }
            .getOrElse(Nil)
        case _ ⇒ Nil // app could not be found, do nothing.
      }

  private def actionsForRunner(app: CloudflowApplication.CR, streamletChangeEvent: StreamletChangeEvent[Secret])(
      implicit system: ActorSystem,
      ctx: DeploymentContext
  ) = {
    import streamletChangeEvent._
    app.spec.deployments
      .find(_.streamletName == streamletName)
      .map { streamletDeployment ⇒
        system.log.info(s"[app: ${app.spec.appId} configuration changed for streamlet $streamletName]")
        val updateLabels = Map(Operator.ConfigUpdateLabel -> System.currentTimeMillis.toString)
        val updateAction = streamletDeployment.runtime match {
          case AkkaRunner.runtime ⇒
            Action.provided[Secret, Deployment](
              streamletDeployment.secretName,
              app.metadata.namespace, {
                case Some(secret) =>
                  val resource =
                    AkkaRunner.resource(streamletDeployment, app, secret, app.metadata.namespace, updateLabels)
                  val labeledResource =
                    resource.copy(metadata = resource.metadata.copy(labels = resource.metadata.labels ++ updateLabels))
                  Action.createOrUpdate(labeledResource, runner.AkkaRunner.editor)
                case None =>
                  throw new Exception(
                    s"Secret ${streamletDeployment.secretName} is missing for streamlet deployment '${streamletDeployment.name}'."
                  )
              }
            )
          case SparkRunner.runtime ⇒
            Action.provided[Secret, SparkResource.CR](
              streamletDeployment.secretName,
              app.metadata.namespace, {
                case Some(secret) =>
                  val resource =
                    SparkRunner.resource(streamletDeployment, app, secret, app.metadata.namespace, updateLabels)
                  val labeledResource =
                    resource.copy(metadata = resource.metadata.copy(labels = resource.metadata.labels ++ updateLabels))
                  val patch = SpecPatch(labeledResource.spec)
                  Action.createOrPatch(resource, patch)(SparkRunner.format, SparkRunner.patchFormat, SparkRunner.resourceDefinition)
                case None =>
                  throw new Exception(
                    s"Secret ${streamletDeployment.secretName} is missing for streamlet deployment '${streamletDeployment.name}'."
                  )
              }
            )
          case FlinkRunner.runtime ⇒
            Action.provided[Secret, FlinkResource.CR](
              streamletDeployment.secretName,
              app.metadata.namespace, {
                case Some(secret) =>
                  val resource =
                    FlinkRunner.resource(streamletDeployment, app, secret, app.metadata.namespace, updateLabels)
                  val labeledResource =
                    resource.copy(metadata = resource.metadata.copy(labels = resource.metadata.labels ++ updateLabels))
                  Action.createOrUpdate(labeledResource, runner.FlinkRunner.editor)
                case None =>
                  throw new Exception(
                    s"Secret ${streamletDeployment.secretName} is missing for streamlet deployment '${streamletDeployment.name}'."
                  )

              }
            )
        }
        val streamletChangeEventAction =
          EventActions.streamletChangeEvent(app, streamletDeployment, namespace, watchEvent._object)

        List(updateAction, streamletChangeEventAction)
      }
      .getOrElse(Nil)
  }
}
