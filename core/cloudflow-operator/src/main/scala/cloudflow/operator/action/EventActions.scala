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

package cloudflow.operator.action
import java.time.ZonedDateTime

import cloudflow.blueprint.deployment.StreamletDeployment
import cloudflow.operator.action.EventActions.EventType.EventType
import cloudflow.operator.runner.{ AkkaRunner, FlinkRunner, SparkRunner }
import cloudflow.operator.{ CloudflowApplication, CloudflowLabels, DeploymentContext }
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

  def deployEvents(app: CloudflowApplication.CR, currentApp: Option[CloudflowApplication.CR], namespace: String, cause: ObjectResource)(
      implicit ctx: DeploymentContext
  ): Seq[Action[ObjectResource]] = {

    val (reason, message) = currentApp match {
      case Some(_) ⇒ ("ApplicationUpdated", s"Updated Cloudflow Application ${app.spec.appId} to namespace ${namespace}")
      case _       ⇒ ("ApplicationDeployed", s"Deployed Cloudflow Application ${app.spec.appId} to namespace ${namespace}")
    }

    val deployEvent = createEvent(
      app = app,
      namespace = namespace,
      reason = reason,
      message = message,
      objectReference = cause
    )
    deployEvent +: streamletScaledEvents(app, currentApp, namespace, cause)
  }

  /**
   * Create StreamletScaled events. Streamlet replica counts are part of a [[cloudflow.blueprint.deployment.StreamletDeployment]] in a
   * [[cloudflow.operator.CloudflowApplication]]. Since it's in the app spec and not the streamlet secret it's not reported as a
   * [[cloudflow.operator.event.StreamletChangeEvent]]
   */
  private def streamletScaledEvents(app: CloudflowApplication.CR,
                                    currentAppOpt: Option[CloudflowApplication.CR],
                                    namespace: String,
                                    cause: ObjectResource)(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] =
    for {
      currentApp       ← currentAppOpt.toVector
      streamlet        ← app.spec.deployments
      currentStreamlet ← currentApp.spec.deployments.find(_.name == streamlet.name)
      if currentStreamlet.replicas != streamlet.replicas
      replicas        = replicasOrRunnerDefault(streamlet)
      currentReplicas = replicasOrRunnerDefault(currentStreamlet)
    } yield createEvent(
      app = app,
      namespace = namespace,
      reason = "StreamletScaled",
      message =
        s"Scaled Cloudflow Application ${app.spec.appId} streamlet ${streamlet.name} in namespace ${namespace} from ${currentReplicas} to ${replicas}",
      objectReference = cause,
      fieldPath = Some(s"spec.deployments{${streamlet.name}}")
    )

  private def replicasOrRunnerDefault(streamlet: StreamletDeployment) = streamlet.runtime match {
    case AkkaRunner.runtime  ⇒ streamlet.replicas.getOrElse(AkkaRunner.NrOfReplicas)
    case SparkRunner.runtime ⇒ streamlet.replicas.getOrElse(SparkRunner.DefaultNrOfExecutorInstances)
    case FlinkRunner.runtime ⇒ streamlet.replicas.getOrElse(FlinkRunner.DefaultParallelism)
  }

  def undeployEvent(app: CloudflowApplication.CR, namespace: String, cause: ObjectResource)(
      implicit ctx: DeploymentContext
  ): Action[ObjectResource] =
    createEvent(
      app = app,
      namespace = namespace,
      reason = "ApplicationUndeployed",
      message = s"Undeployed Cloudflow Application ${app.spec.appId} from namespace ${namespace}",
      objectReference = cause
    )

  def streamletChangeEvent(app: CloudflowApplication.CR, streamlet: StreamletDeployment, namespace: String, cause: ObjectResource)(
      implicit ctx: DeploymentContext
  ): Action[ObjectResource] =
    createEvent(
      app = app,
      namespace = namespace,
      reason = "StreamletConfigurationChanged",
      message =
        s"Changed streamlet configuration of Cloudflow Application ${app.spec.appId} streamlet ${streamlet.name} in namespace ${namespace}",
      objectReference = cause
    )

  private[operator] def createEvent(app: CloudflowApplication.CR,
                                    namespace: String,
                                    reason: String,
                                    message: String,
                                    `type`: EventType = EventType.Normal,
                                    objectReference: skuber.ObjectReference,
                                    fieldPath: Option[String] = None)(implicit ctx: DeploymentContext): CreateAction[Event] = {
    val eventTime    = ZonedDateTime.now()
    val metadataName = newEventName(ctx.podName, app.spec.appId)

    // the object reference fieldPath is irrelevant for application events.
    val refMaybeWithPath = fieldPath.map(path ⇒ objectReference.copy(fieldPath = path)).getOrElse(objectReference)

    val event = Event(
      metadata = ObjectMeta(
        name = metadataName,
        namespace = namespace,
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

    Action.create(event, eventEditor)
  }

  private def newEventName(sourceResource: String, appId: String): String = {
    val uuid = java.util.UUID.randomUUID().toString.take(5)
    s"$sourceResource.$appId.$uuid"
  }
}
