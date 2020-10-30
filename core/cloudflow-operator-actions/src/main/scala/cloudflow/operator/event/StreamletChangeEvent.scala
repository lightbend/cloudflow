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

import akka.actor._
import akka.NotUsed

import org.slf4j.LoggerFactory

import skuber._
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
                   event: StreamletChangeEvent[Secret])(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] =
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
                streamletChangeEvent
              )
            } else Nil
          }
          .getOrElse(Nil)
      case _ ⇒ Nil // app could not be found, do nothing.
    }

  def actionsForRunner(app: CloudflowApplication.CR, streamletChangeEvent: StreamletChangeEvent[Secret])(
      implicit
      ctx: DeploymentContext
  ) = {
    import streamletChangeEvent._
    app.spec.deployments
      .find(_.streamletName == streamletName)
      .map { streamletDeployment ⇒
        log.info(s"[app: ${app.spec.appId} configuration changed for streamlet $streamletName]")
        val updateLabels = Map(CloudflowLabels.ConfigUpdateLabel -> System.currentTimeMillis.toString)
        val updateAction = streamletDeployment.runtime match {
          case AkkaRunner.runtime ⇒
            Action.provided[Secret, ObjectResource](
              streamletDeployment.secretName,
              app.metadata.namespace, {
                case Some(secret) =>
                  val resource =
                    AkkaRunner.resource(streamletDeployment, app, secret, app.metadata.namespace, updateLabels)
                  val labeledResource =
                    resource.copy(metadata = resource.metadata.copy(labels = resource.metadata.labels ++ updateLabels))
                  Action.createOrUpdate(labeledResource, runner.AkkaRunner.editor)
                case None =>
                  val msg = s"Secret ${streamletDeployment.secretName} is missing for streamlet deployment '${streamletDeployment.name}'."
                  log.error(msg)
                  CloudflowApplication.Status.errorAction(app, msg)
              }
            )
          case SparkRunner.runtime ⇒
            Action.provided[Secret, ObjectResource](
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
                  val msg = s"Secret ${streamletDeployment.secretName} is missing for streamlet deployment '${streamletDeployment.name}'."
                  log.error(msg)
                  CloudflowApplication.Status.errorAction(app, msg)
              }
            )
          case FlinkRunner.runtime ⇒
            Action.provided[Secret, ObjectResource](
              streamletDeployment.secretName,
              app.metadata.namespace, {
                case Some(secret) =>
                  val resource =
                    FlinkRunner.resource(streamletDeployment, app, secret, app.metadata.namespace, updateLabels)
                  val labeledResource =
                    resource.copy(metadata = resource.metadata.copy(labels = resource.metadata.labels ++ updateLabels))
                  Action.createOrUpdate(labeledResource, runner.FlinkRunner.editor)
                case None =>
                  val msg = s"Secret ${streamletDeployment.secretName} is missing for streamlet deployment '${streamletDeployment.name}'."

                  log.error(msg)
                  CloudflowApplication.Status.errorAction(app, msg)
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
