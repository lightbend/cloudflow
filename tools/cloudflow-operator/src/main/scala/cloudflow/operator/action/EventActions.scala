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
import akka.datap.crd.App
import akka.kube.actions.Action

import java.time.ZonedDateTime
import cloudflow.blueprint.deployment.StreamletDeployment
import cloudflow.operator.action.EventActions.EventType.EventType
import cloudflow.operator.action.runner.Runner
import io.fabric8.kubernetes.api.model.{ EventBuilder, EventSourceBuilder, ObjectReference }
import io.fabric8.kubernetes.client.VersionInfo.VersionKeys

import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._

object EventActions {
  private[operator] val OperatorSource = new EventSourceBuilder().withComponent("cloudflow-operator").build()

  private[action] object EventType extends Enumeration {
    type EventType = Value
    val Normal, Warning, Error = Value
  }

  def deployEvents(
      app: App.Cr,
      currentApp: Option[App.Cr],
      runners: Map[String, Runner[_]],
      podName: String,
      cause: ObjectReference): Seq[Action] = {

    val (reason, message) = currentApp match {
      case Some(_) =>
        ("ApplicationUpdated", s"Updated Cloudflow Application ${app.spec.appId} to namespace ${app.namespace}")
      case _ =>
        ("ApplicationDeployed", s"Deployed Cloudflow Application ${app.spec.appId} to namespace ${app.namespace}")
    }

    val deployEvent =
      createEvent(app = app, podName = podName, reason = reason, message = message, objectReference = cause)
    deployEvent +: streamletScaledEvents(app, currentApp, runners, podName, cause)
  }

  /**
   * Create StreamletScaled events. Streamlet replica counts are part of a [[cloudflow.blueprint.deployment.StreamletDeployment]] in a
   * [[cloudflow.operator.CloudflowApplication]]. Since it's in the app spec and not the streamlet secret it's not reported as a
   * [[cloudflow.operator.event.StreamletChangeEvent]]
   */
  private def streamletScaledEvents(
      app: App.Cr,
      currentAppOpt: Option[App.Cr],
      runners: Map[String, Runner[_]],
      podName: String,
      cause: ObjectReference): Seq[Action] =
    for {
      currentApp <- currentAppOpt.toVector
      streamlet <- app.spec.deployments
      currentStreamlet <- currentApp.spec.deployments.find(_.name == streamlet.name)
      if currentStreamlet.replicas != streamlet.replicas
      replicas = replicasOrRunnerDefault(streamlet, runners)
      currentReplicas = replicasOrRunnerDefault(currentStreamlet, runners)
    } yield createEvent(
      app = app,
      podName = podName,
      reason = "StreamletScaled",
      message =
        s"Scaled Cloudflow Application ${app.spec.appId} streamlet ${streamlet.name} in namespace ${app.namespace} from ${currentReplicas} to ${replicas}",
      objectReference = cause,
      fieldPath = Some(s"spec.deployments{${streamlet.name}}"))

  private def replicasOrRunnerDefault(streamlet: App.Deployment, runners: Map[String, Runner[_]]) =
    runners.get(streamlet.runtime).map(runner => streamlet.replicas.getOrElse(runner.defaultReplicas)).getOrElse(0)

  def undeployEvent(app: App.Cr, podName: String, cause: ObjectReference): Action =
    createEvent(
      app = app,
      podName = podName,
      reason = "ApplicationUndeployed",
      message = s"Undeployed Cloudflow Application ${app.spec.appId} from namespace ${app.namespace}",
      objectReference = cause)

  def streamletChangeEvent(app: App.Cr, streamlet: App.Deployment, podName: String, cause: ObjectReference): Action =
    createEvent(
      app = app,
      podName = podName,
      reason = "StreamletConfigurationChanged",
      message =
        s"Changed streamlet configuration of Cloudflow Application ${app.spec.appId} streamlet ${streamlet.name} in namespace ${app.namespace}",
      objectReference = cause)

  private[operator] def createEvent(
      app: App.Cr,
      podName: String,
      reason: String,
      message: String,
      `type`: EventType = EventType.Normal,
      objectReference: ObjectReference,
      fieldPath: Option[String] = None): Action = {
    val eventTime = ZonedDateTime.now()
    val metadataName = newEventName(podName, app.spec.appId)

    // the object reference fieldPath is irrelevant for application events.
    fieldPath match {
      case Some(path) => objectReference.setFieldPath(path)
      case _          =>
    }

    val event = new EventBuilder()
      .withNewMetadata()
      .withName(metadataName)
      .withNamespace(app.namespace)
      .withLabels(CloudflowLabels(app).baseLabels.asJava)
      .endMetadata()
      .withInvolvedObject(objectReference)
      .withReason(reason)
      .withMessage(message)
      .withType(`type`.toString)
      .withFirstTimestamp(eventTime.format(DateTimeFormatter.ISO_INSTANT))
      .withLastTimestamp(eventTime.format(DateTimeFormatter.ISO_INSTANT))
      .withCount(1)
      .withSource(OperatorSource)
      .build()

    Action.createOrReplace(event)
  }

  private def newEventName(sourceResource: String, appId: String): String = {
    val uuid = java.util.UUID.randomUUID().toString.take(5)
    s"$sourceResource.$appId.$uuid"
  }
}
