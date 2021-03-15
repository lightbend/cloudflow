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
import akka.kube.actions.{ Action, CustomResourceAdapter }
import cloudflow.operator.action.runner.Runner
import io.fabric8.kubernetes.api.model.{ ContainerState, Pod }
import io.fabric8.kubernetes.api.{ model => fabric8 }
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ MixedOperation, Resource }
import org.slf4j.LoggerFactory

import scala.collection.immutable._
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }

object CloudflowStatus {
  private val log = LoggerFactory.getLogger(Status.getClass)

  object PodStatus {
    val Pending = "Pending"
    val Running = "Running"
    val Terminating = "Terminating"
    val Terminated = "Terminated"
    val Succeeded = "Succeeded"
    val Failed = "Failed"
    val Unknown = "Unknown"
    val CrashLoopBackOff = "CrashLoopBackOff"

    val ReadyTrue = "True"
    val ReadyFalse = "False"
  }

  private def podReady(ps: App.PodStatus) = {
    ps.status == PodStatus.Running && ps.nrOfContainersReady == ps.nrOfContainers && ps.nrOfContainers > 0
  }

  private def podStatus(
      name: String,
      status: String,
      restarts: Int,
      nrOfContainersReady: Int,
      nrOfContainers: Int): App.PodStatus = {
    val ready =
      if (nrOfContainersReady == nrOfContainers && nrOfContainers > 0) PodStatus.ReadyTrue else PodStatus.ReadyFalse
    App.PodStatus(
      name = name,
      status = status,
      restarts = restarts,
      nrOfContainersReady = nrOfContainersReady,
      nrOfContainers = nrOfContainers,
      ready = ready)
  }

  def fromPod(pod: Pod): App.PodStatus = {
    // See https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/
    val name: String = pod.getMetadata().getName()
    val status: fabric8.PodStatus = Option(pod.getStatus()).getOrElse(new fabric8.PodStatus())
    val nrOfContainers: Int = Try { // protect from null pointers
      Option(pod.getSpec())
        .map(_.getContainers().size())
        .getOrElse(status.getContainerStatuses.size())
    }.getOrElse(0)
    val nrOfContainersReady: Int = Try { status.getContainerStatuses.asScala.filter(_.getReady).size }.getOrElse(0)
    val restarts: Int = Try { status.getContainerStatuses.asScala.map(_.getRestartCount.intValue()).sum }.getOrElse(0)
    val containerStates = Try { status.getContainerStatuses.asScala.map(_.getState).toList }.getOrElse(List())

    // https://github.com/kubernetes/kubectl/blob/27fa797464ff6684ec591c46c69d5e013998a0d1/pkg/describe/describe.go#L698
    val st = if (pod.getMetadata.getDeletionTimestamp != null && pod.getMetadata.getDeletionTimestamp.nonEmpty) {
      PodStatus.Terminating
    } else {
      Try(status.getPhase).toOption match {
        case Some(PodStatus.Pending)   => getStatusFromContainerStates(containerStates, nrOfContainers)
        case Some(PodStatus.Running)   => getStatusFromContainerStates(containerStates, nrOfContainers)
        case Some(PodStatus.Succeeded) => PodStatus.Succeeded
        case Some(PodStatus.Failed)    => PodStatus.Failed
        case Some(PodStatus.Unknown)   => PodStatus.Unknown
        case _                         => getStatusFromContainerStates(containerStates, nrOfContainers)
      }
    }

    podStatus(
      name = name,
      status = st,
      restarts = restarts,
      nrOfContainersReady = nrOfContainersReady,
      nrOfContainers = nrOfContainers)
  }

  private def getStatusFromContainerStates(containerStates: List[ContainerState], nrOfContainers: Int): String =
    if (containerStates.nonEmpty) {
      // - Running if all containers running;
      // - Terminated if all containers terminated;
      // - first Waiting reason found (ContainerCreating, CrashLoopBackOff, ErrImagePull, ...);
      // - otherwise Pending.
      if (containerStates.filter(_.getRunning != null).size == nrOfContainers) PodStatus.Running
      else if (containerStates.filter(_.getTerminated != null).size == nrOfContainers) PodStatus.Terminated
      else {
        containerStates
          .filter(_.getWaiting != null)
          .headOption
          .map(_.getWaiting.getReason)
          .getOrElse(PodStatus.Pending)
      }
    } else PodStatus.Pending

  private def hasExpectedPods(streamlet: App.StreamletStatus)(nrOfPodsDetected: Int) =
    streamlet.expectedPodCount.getOrElse(0) == nrOfPodsDetected

  private def updatePod(streamlet: App.StreamletStatus)(pod: Pod) = {
    val podStatus = fromPod(pod)
    streamlet.copy(podStatuses = streamlet.podStatuses.filterNot(_.name == podStatus.name) :+ podStatus)
  }
  private def deletePod(streamlet: App.StreamletStatus)(pod: Pod): App.StreamletStatus = {
    streamlet.copy(podStatuses = streamlet.podStatuses.filterNot(_.name == pod.getMetadata().getName()))
  }

  object Status {
    val Running = "Running"
    val Pending = "Pending"
    val CrashLoopBackOff = "CrashLoopBackOff"
    val Error = "Error"
  }

  def freshStatus(spec: App.Spec, runners: Map[String, Runner[_]]): App.AppStatus = {
    val streamletStatuses = createStreamletStatuses(spec, runners)
    App.AppStatus(
      appId = spec.appId,
      appVersion = spec.appVersion,
      appStatus = Status.Pending,
      appMessage = "",
      endpointStatuses = Nil,
      streamletStatuses = streamletStatuses)
  }

  def pendingAction(app: App.Cr, runners: Map[String, Runner[_]], msg: String): Action = {
    log.info(s"Setting pending status for app ${app.spec.appId}")
    val newStatus =
      freshStatus(app.spec, runners).copy(appStatus = Status.Pending, appMessage = msg)
    app.setStatus(newStatus)

    statusUpdateAction(app)()
  }

  def errorAction(app: App.Cr, runners: Map[String, Runner[_]], msg: String): Action = {
    log.info(s"Setting error status for app ${app.spec.appId}")
    val newStatus = freshStatus(app.spec, runners)
      .copy(
        appStatus = Status.Error,
        appMessage = s"An unrecoverable error has occured, please undeploy the application. Reason: ${msg}")
    app.setStatus(newStatus)

    statusUpdateAction(app)()
  }

  private def createStreamletStatuses(spec: App.Spec, runners: Map[String, Runner[_]]) =
    spec.deployments.map { deployment =>
      val expectedPodCount = runners.get(deployment.runtime).map(_.expectedPodCount(deployment)).getOrElse(1)
      App.StreamletStatus(
        streamletName = deployment.streamletName,
        expectedPodCount = Some(expectedPodCount),
        podStatuses = Nil)
    }.toVector

  private def calcAppStatus(streamletStatuses: Seq[App.StreamletStatus]): String = {
    if (streamletStatuses.forall { streamletStatus =>
          hasExpectedPods(streamletStatus)(streamletStatus.podStatuses.size) &&
          streamletStatus.podStatuses.forall(podReady)
        }) {
      Status.Running
    } else if (streamletStatuses.flatMap(_.podStatuses).exists(_.status == PodStatus.CrashLoopBackOff)) {
      Status.CrashLoopBackOff
    } else {
      Status.Pending
    }
  }

  def aggregatedStatus(status: App.AppStatus) = {
    if (status.appStatus == null || status.appStatus.isEmpty) {
      Status.Pending
    } else {
      status.appStatus
    }
  }

  def updateApp(newApp: App.Cr, runners: Map[String, Runner[_]]): App.Cr = {
    val newStreamletStatuses = createStreamletStatuses(newApp.spec, runners).map { newStreamletStatus =>
      Try { newApp.getStatus.streamletStatuses }
        .getOrElse(Nil)
        .find(_.streamletName == newStreamletStatus.streamletName)
        .map { streamletStatus =>
          newStreamletStatus.copy(podStatuses = streamletStatus.podStatuses)
        }
        .getOrElse(newStreamletStatus)
    }
    val newStatus = newApp.getStatus.copy(
      appId = newApp.spec.appId,
      appVersion = newApp.spec.appVersion,
      streamletStatuses = newStreamletStatuses,
      appStatus = calcAppStatus(newStreamletStatuses))

    newApp.setStatus(newStatus)
    newApp
  }

  def updatePod(status: App.AppStatus)(streamletName: String, pod: Pod): App.AppStatus = {
    val streamlets = Try { status.streamletStatuses }.getOrElse(Nil)
    val streamletStatus =
      streamlets
        .find(_.streamletName == streamletName)
        .map { streamletStatus => updatePod(streamletStatus)(pod) }
        .toList
    val streamletStatusesUpdated =
      streamlets.filterNot(_.streamletName == streamletName) ++ streamletStatus

    status.copy(streamletStatuses = streamletStatusesUpdated, appStatus = calcAppStatus(streamletStatusesUpdated))
  }

  def deletePod(status: App.AppStatus)(streamletName: String, pod: Pod): App.AppStatus = {
    val streamlets = Try { status.streamletStatuses }.getOrElse(Nil)
    val streamletStatus =
      streamlets
        .find(_.streamletName == streamletName)
        .map { streamletStatus => deletePod(streamletStatus)(pod) }
        .toList
    val streamletStatusesUpdated =
      streamlets.filterNot(_.streamletName == streamletName) ++ streamletStatus
    status.copy(streamletStatuses = streamletStatusesUpdated, appStatus = calcAppStatus(streamletStatusesUpdated))
  }

  private def ignoreOnErrorStatus(oldApp: Option[App.Cr], newApp: App.Cr) = {
    val _ = newApp
    oldApp.map(_.getStatus) match {
      case Some(status) if status.appStatus == Some(Status.Error) => false
      case _                                                      => true
    }
  }

  implicit val adapter =
    CustomResourceAdapter[App.Cr, App.List](App.customResourceDefinitionContext)

  // TODO: this failure still happen, seldom and not reproduciple ...
  // maybe I can reproduce and battle harden deploying 2 operators!
  def statusUpdateAction(app: App.Cr)(retry: Int = 5): Action = {
    Action.operation[App.Cr, App.List, Try[App.Cr]](
      { client: KubernetesClient =>
        client
          .customResources(App.customResourceDefinitionContext, classOf[App.Cr], classOf[App.List])
      }, { cr: MixedOperation[App.Cr, App.List, Resource[App.Cr]] =>
        Try {
          val current =
            cr.inNamespace(app.namespace)
              .withName(app.name)

          Option(current.fromServer().get()) match {
            case Some(curr) =>
              curr.setStatus(app.getStatus)

              val newAppWithStatus = App.Cr(spec = null, metadata = curr.getMetadata, status = curr.getStatus)
              current.updateStatus(newAppWithStatus)
            case _ =>
              if (retry > 0) {
                throw new Exception("Trying to update a CR that has been deleted")
              } else {
                log.warn(s"Cannot yet find the CR to update, giving up")
                App.Cr(spec = null, metadata = null)
              }
          }
        }
      }, { res =>
        res match {
          case Success(_) => Action.noop
          case Failure(err) if retry > 0 =>
            log.error(s"Failure updating the CR status retries: $retry", err)
            Thread.sleep(100)
            statusUpdateAction(app)(retry - 1)
          case Failure(err) =>
            log.error("Failure updating the CR status retries exhausted, giving up", err)
            throw err
        }
      })
  }

}
