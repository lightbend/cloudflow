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

package cloudflow.operator.action
import java.time.ZonedDateTime

import cloudflow.blueprint.deployment.StreamletDeployment
import cloudflow.operator.action.EventActions.EventType.EventType
import cloudflow.operator.action.runner.Runner
import skuber.json.format.eventFmt
import skuber.{ Event, ObjectEditor, ObjectMeta, ObjectResource }

object EventActions {
  private[operator] val OperatorSource = Event.Source(Some("cloudflow-operator"))

  private[operator] val eventEditor = new ObjectEditor[Event] {
    override def updateMetadata(obj: Event, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }

  private[action] object EventType extends Enumeration {
    type EventType = Value
    val Normal, Warning, Error = Value
  }

  def deployEvents(app: CloudflowApplication.CR,
                   currentApp: Option[CloudflowApplication.CR],
                   runners: Map[String, Runner[_]],
                   podName: String,
                   cause: ObjectResource): Seq[Action] = {

    val (reason, message) = currentApp match {
      case Some(_) => ("ApplicationUpdated", s"Updated Cloudflow Application ${app.spec.appId} to namespace ${app.namespace}")
      case _       => ("ApplicationDeployed", s"Deployed Cloudflow Application ${app.spec.appId} to namespace ${app.namespace}")
    }

    val deployEvent = createEvent(
      app = app,
      podName = podName,
      reason = reason,
      message = message,
      objectReference = cause
    )
    deployEvent +: streamletScaledEvents(app, currentApp, runners, podName, cause)
  }

  /**
   * Create StreamletScaled events. Streamlet replica counts are part of a [[cloudflow.blueprint.deployment.StreamletDeployment]] in a
   * [[cloudflow.operator.CloudflowApplication]]. Since it's in the app spec and not the streamlet secret it's not reported as a
   * [[cloudflow.operator.event.StreamletChangeEvent]]
   */
  private def streamletScaledEvents(app: CloudflowApplication.CR,
                                    currentAppOpt: Option[CloudflowApplication.CR],
                                    runners: Map[String, Runner[_]],
                                    podName: String,
                                    cause: ObjectResource): Seq[Action] =
    for {
      currentApp       <- currentAppOpt.toVector
      streamlet        <- app.spec.deployments
      currentStreamlet <- currentApp.spec.deployments.find(_.name == streamlet.name) if currentStreamlet.replicas != streamlet.replicas
      replicas        = replicasOrRunnerDefault(streamlet, runners)
      currentReplicas = replicasOrRunnerDefault(currentStreamlet, runners)
    } yield createEvent(
      app = app,
      podName = podName,
      reason = "StreamletScaled",
      message =
        s"Scaled Cloudflow Application ${app.spec.appId} streamlet ${streamlet.name} in namespace ${app.namespace} from ${currentReplicas} to ${replicas}",
      objectReference = cause,
      fieldPath = Some(s"spec.deployments{${streamlet.name}}")
    )

  private def replicasOrRunnerDefault(streamlet: StreamletDeployment, runners: Map[String, Runner[_]]) =
    runners.get(streamlet.runtime).map(runner => streamlet.replicas.getOrElse(runner.defaultReplicas)).getOrElse(0)

  def undeployEvent(app: CloudflowApplication.CR, podName: String, cause: ObjectResource): Action =
    createEvent(
      app = app,
      podName = podName,
      reason = "ApplicationUndeployed",
      message = s"Undeployed Cloudflow Application ${app.spec.appId} from namespace ${app.namespace}",
      objectReference = cause
    )

  def streamletChangeEvent(app: CloudflowApplication.CR, streamlet: StreamletDeployment, podName: String, cause: ObjectResource): Action =
    createEvent(
      app = app,
      podName = podName,
      reason = "StreamletConfigurationChanged",
      message =
        s"Changed streamlet configuration of Cloudflow Application ${app.spec.appId} streamlet ${streamlet.name} in namespace ${app.namespace}",
      objectReference = cause
    )

  private[operator] def createEvent(app: CloudflowApplication.CR,
                                    podName: String,
                                    reason: String,
                                    message: String,
                                    `type`: EventType = EventType.Normal,
                                    objectReference: skuber.ObjectReference,
                                    fieldPath: Option[String] = None): CreateOrUpdateAction[Event] = {
    val eventTime    = ZonedDateTime.now()
    val metadataName = newEventName(podName, app.spec.appId)

    // the object reference fieldPath is irrelevant for application events.
    val refMaybeWithPath = fieldPath.map(path => objectReference.copy(fieldPath = path)).getOrElse(objectReference)

    val event = Event(
      metadata = ObjectMeta(
        name = metadataName,
        namespace = app.namespace,
        labels = CloudflowLabels(app).baseLabels
      ),
      involvedObject = refMaybeWithPath,
      reason = Some(reason),
      message = Some(message),
      `type` = Some(`type`.toString),
      firstTimestamp = Some(eventTime),
      lastTimestamp = Some(eventTime),
      count = Some(1),
      source = Some(OperatorSource)
    )

    Action.createOrUpdate(event, eventEditor)
  }

  private def newEventName(sourceResource: String, appId: String): String = {
    val uuid = java.util.UUID.randomUUID().toString.take(5)
    s"$sourceResource.$appId.$uuid"
  }
}
