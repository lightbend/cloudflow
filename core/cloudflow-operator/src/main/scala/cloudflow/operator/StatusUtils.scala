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

import scala.collection.immutable._

import skuber._

import cloudflow.blueprint.deployment._
import CloudflowApplication._

import cloudflow.operator.action.Action

object StatusUtils {
  val Unknown          = "Unknown"
  val Running          = "Running"
  val Pending          = "Pending"
  val CrashLoopBackOff = "CrashLoopBackOff"

  def makeStatus(
      spec: CloudflowApplication.Spec
  ): Status = {
    val streamletStatuses = createStreamletStatuses(spec)
    Status(
      spec.appId,
      spec.appVersion,
      streamletStatuses,
      Some(Unknown)
    )
  }

  def createStreamletStatuses(spec: CloudflowApplication.Spec) = {
    // TODO not match on runtime, this is not great for extensibility.
    // There are some plans to make replicas mandatory in the CR
    // and to indicate extraPods required in the deployment to prevent the code below specific to runtimes
    import cloudflow.operator.runner._
    spec.deployments.map { deployment =>
      val expectedPodCount = deployment.runtime match {
        case AkkaRunner.runtime  ⇒ deployment.replicas.getOrElse(AkkaRunner.DefaultReplicas)
        case SparkRunner.runtime ⇒ deployment.replicas.getOrElse(SparkRunner.DefaultNrOfExecutorInstances) + 1
        case FlinkRunner.runtime ⇒ deployment.replicas.getOrElse(FlinkRunner.DefaultReplicas) + 1
      }
      StreamletStatus(
        deployment.streamletName,
        expectedPodCount
      )
    }
  }

  def calcAppStatus(streamletStatuses: Vector[StreamletStatus]): String =
    if (streamletStatuses.forall { streamletStatus =>
          streamletStatus.hasExpectedPods(streamletStatus.podStatuses.size) &&
          streamletStatus.podStatuses.forall(_.isReady)
        }) {
      Status.Running
    } else if (streamletStatuses.flatMap(_.podStatuses).exists(_.status == PodStatus.CrashLoopBackOff)) {
      Status.CrashLoopBackOff
    } else {
      Status.Pending
    }

  def updateApp(currentStatus: Status, newApp: CloudflowApplication.CR) = {
    // copy PodStatus lists that already exist
    import currentStatus._
    val newStreamletStatuses = StatusUtils.createStreamletStatuses(newApp.spec).map { newStreamletStatus =>
      streamletStatuses
        .find(_.streamletName == newStreamletStatus.streamletName)
        .map { streamletStatus =>
          newStreamletStatus.copy(podStatuses = streamletStatus.podStatuses)
        }
        .getOrElse(newStreamletStatus)
    }
    currentStatus.copy(
      appId = newApp.spec.appId,
      appVersion = newApp.spec.appVersion,
      streamletStatuses = newStreamletStatuses,
      appStatus = Some(StatusUtils.calcAppStatus(newStreamletStatuses))
    )
  }

  def updatePod(currentStatus: Status, streamletName: String, pod: Pod) = {
    val streamletStatus =
      currentStatus.streamletStatuses
        .find(_.streamletName == streamletName)
        .map(_.updatePod(pod))
        .toList
    val streamletStatusesUpdated = currentStatus.streamletStatuses.filterNot(_.streamletName == streamletName) ++ streamletStatus
    currentStatus.copy(
      streamletStatuses = streamletStatusesUpdated,
      appStatus = Some(StatusUtils.calcAppStatus(streamletStatusesUpdated))
    )
  }

  def deletePod(currentStatus: Status, streamletName: String, pod: Pod) = {
    val streamletStatus =
      currentStatus.streamletStatuses
        .find(_.streamletName == streamletName)
        .map(_.deletePod(pod))
        .toList
    val streamletStatusesUpdated = currentStatus.streamletStatuses.filterNot(_.streamletName == streamletName) ++ streamletStatus
    currentStatus.copy(
      streamletStatuses = streamletStatusesUpdated,
      appStatus = Some(StatusUtils.calcAppStatus(streamletStatusesUpdated))
    )
  }

  def toAction(currentStatus: Status, app: CloudflowApplication.CR): Action[ObjectResource] =
    Action.updateStatus(app.withStatus(currentStatus), editor)
}
