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

import java.security.MessageDigest

import scala.collection.immutable._
import scala.util.Try
import com.typesafe.config._

import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

import skuber._
import skuber.apiextensions._
import skuber.ResourceSpecification.Subresources

import cloudflow.blueprint._
import cloudflow.blueprint.deployment.{ Topic => AppDescriptorTopic, _ }

import cloudflow.operator.action.Action

/**
 * CloudflowApplication Custom Resource.
 */
object CloudflowApplication {

  val PrometheusAgentKey = "prometheus"

  final case class Spec(
      appId: String,
      appVersion: String,
      streamlets: Vector[StreamletInstance],
      deployments: Vector[StreamletDeployment],
      agentPaths: Map[String, String]
  )

  implicit val config = JsonConfiguration(SnakeCase)

  type CR = CustomResource[Spec, Status]

  def editor = new ObjectEditor[CR] {
    def updateMetadata(obj: CR, newMetadata: ObjectMeta): CR = obj.copy(metadata = newMetadata)
  }

  implicit val streamletAttributeDescriptorFmt: Format[StreamletAttributeDescriptor] = Json.format[StreamletAttributeDescriptor]
  implicit val streamletRuntimeDescriptorFmt: Format[StreamletRuntimeDescriptor] = Format[StreamletRuntimeDescriptor](
    Reads { jsValue ⇒
      jsValue match {
        case JsString(runtime) ⇒ JsSuccess(StreamletRuntimeDescriptor(runtime))
        case _                 ⇒ JsError("Expected JsString for StreamletRuntimeDescriptor")
      }
    },
    Writes(r ⇒ JsString(r.name))
  )
  implicit val schemaDescriptorFmt: Format[SchemaDescriptor]                   = Json.format[SchemaDescriptor]
  implicit val outletDescriptorFmt: Format[OutletDescriptor]                   = Json.format[OutletDescriptor]
  implicit val inletDescriptorFmt: Format[InletDescriptor]                     = Json.format[InletDescriptor]
  implicit val configParameterDescriptorFmt: Format[ConfigParameterDescriptor] = Json.format[ConfigParameterDescriptor]
  implicit val volumeMountDescriptorFmt: Format[VolumeMountDescriptor]         = Json.format[VolumeMountDescriptor]
  implicit val streamletDescriptorFormat: Format[StreamletDescriptor]          = Json.format[StreamletDescriptor]
  implicit val streamletFmt: Format[StreamletInstance]                         = Json.format[StreamletInstance]

  implicit val configFmt: Format[Config] = Format[Config](
    Reads(jsValue ⇒
      Try(ConfigFactory.parseString(jsValue.toString)).fold[JsResult[Config]](e ⇒ JsError(e.getMessage), conf ⇒ JsSuccess(conf))
    ),
    Writes(conf ⇒ Json.parse(conf.root().render(ConfigRenderOptions.concise())))
  )
  implicit val savepointFmt: Format[AppDescriptorTopic]   = Json.format[AppDescriptorTopic]
  implicit val endpointFmt: Format[Endpoint]              = Json.format[Endpoint]
  implicit val deploymentFmt: Format[StreamletDeployment] = Json.format[StreamletDeployment]

  implicit val SpecFmt: Format[Spec]                       = Json.format[Spec]
  implicit val PodStatusFmt: Format[PodStatus]             = Json.format[PodStatus]
  implicit val StreamletStatusFmt: Format[StreamletStatus] = Json.format[StreamletStatus]
  implicit val StatusFmt: Format[Status]                   = Json.format[Status]

  implicit val Definition = ResourceDefinition[CustomResource[Spec, Status]](
    group = "cloudflow.lightbend.com",
    version = "v1alpha1",
    kind = "CloudflowApplication",
    singular = Some("cloudflowapplication"),
    plural = Some("cloudflowapplications"),
    shortNames = List("cloudflowapp"),
    subresources = Some(Subresources().withStatusSubresource)
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[CR]

  val CRD = CustomResourceDefinition[CR]

  def apply(applicationSpec: CloudflowApplication.Spec): CR =
    CustomResource(
      applicationSpec
    )

  def hash(applicationSpec: CloudflowApplication.Spec): String = {
    val jsonString = Json.stringify(Json.toJson(CloudflowApplication(applicationSpec)))

    def bytesToHexString(bytes: Array[Byte]): String =
      bytes.map("%02x".format(_)).mkString
    val md = MessageDigest.getInstance("MD5")
    md.update(jsonString.getBytes("UTF8"))
    bytesToHexString(md.digest())
  }

  object Status {
    val Unknown          = "Unknown"
    val Running          = "Running"
    val Pending          = "Pending"
    val CrashLoopBackOff = "CrashLoopBackOff"

    def apply(
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
  }

  // the status is created with the expected number of streamlet statuses, derived from the CloudflowApplication.Spec, see companion
  case class Status private (appId: String, appVersion: String, streamletStatuses: Vector[StreamletStatus], appStatus: Option[String]) {
    def aggregatedStatus = appStatus.getOrElse(Status.Unknown)
    def updateApp(newApp: CloudflowApplication.CR) = {
      // copy PodStatus lists that already exist
      val newStreamletStatuses = Status.createStreamletStatuses(newApp.spec).map { newStreamletStatus =>
        streamletStatuses
          .find(_.streamletName == newStreamletStatus.streamletName)
          .map { streamletStatus =>
            newStreamletStatus.copy(podStatuses = streamletStatus.podStatuses)
          }
          .getOrElse(newStreamletStatus)
      }
      copy(
        appId = newApp.spec.appId,
        appVersion = newApp.spec.appVersion,
        streamletStatuses = newStreamletStatuses,
        appStatus = Some(Status.calcAppStatus(newStreamletStatuses))
      )
    }

    def updatePod(streamletName: String, pod: Pod) = {
      val streamletStatus =
        streamletStatuses
          .find(_.streamletName == streamletName)
          .map(_.updatePod(pod))
          .toList
      val streamletStatusesUpdated = streamletStatuses.filterNot(_.streamletName == streamletName) ++ streamletStatus
      copy(
        streamletStatuses = streamletStatusesUpdated,
        appStatus = Some(Status.calcAppStatus(streamletStatusesUpdated))
      )
    }

    def deletePod(streamletName: String, pod: Pod) = {
      val streamletStatus =
        streamletStatuses
          .find(_.streamletName == streamletName)
          .map(_.deletePod(pod))
          .toList
      val streamletStatusesUpdated = streamletStatuses.filterNot(_.streamletName == streamletName) ++ streamletStatus
      copy(
        streamletStatuses = streamletStatusesUpdated,
        appStatus = Some(Status.calcAppStatus(streamletStatusesUpdated))
      )
    }

    def toAction(app: CloudflowApplication.CR): Action[ObjectResource] =
      Action.updateStatus(app.withStatus(this), editor)
  }

  object StreamletStatus {
    def apply(streamletName: String, expectedPodCount: Int): StreamletStatus =
      StreamletStatus(streamletName, Some(expectedPodCount), Vector())
    def apply(streamletName: String, pod: Pod, expectedPodCount: Int): StreamletStatus =
      StreamletStatus(streamletName, Some(expectedPodCount), Vector(PodStatus(pod)))
    def apply(streamletName: String, expectedPodCount: Int, podStatuses: Vector[PodStatus]): StreamletStatus =
      StreamletStatus(streamletName, Some(expectedPodCount), podStatuses)
  }

  case class StreamletStatus(
      streamletName: String,
      // Added as Option for backwards compatibility
      expectedPodCount: Option[Int],
      podStatuses: Vector[PodStatus]
  ) {
    def hasExpectedPods(nrOfPodsDetected: Int) = expectedPodCount.getOrElse(0) == nrOfPodsDetected
    def updatePod(pod: Pod) = {
      val podStatus = PodStatus(pod)
      copy(podStatuses = podStatuses.filterNot(_.name == podStatus.name) :+ podStatus)
    }
    def deletePod(pod: Pod) = copy(podStatuses = podStatuses.filterNot(_.name == pod.metadata.name))
  }

  object PodStatus {
    val Pending          = "Pending"
    val Running          = "Running"
    val Terminating      = "Terminating"
    val Terminated       = "Terminated"
    val Succeeded        = "Succeeded"
    val Failed           = "Failed"
    val Unknown          = "Unknown"
    val CrashLoopBackOff = "CrashLoopBackOff"

    val ReadyTrue  = "True"
    val ReadyFalse = "False"

    def apply(
        name: String,
        status: String,
        restarts: Int,
        nrOfContainersReady: Int,
        nrOfContainers: Int
    ): PodStatus = {
      val ready = if (nrOfContainersReady == nrOfContainers && nrOfContainers > 0) ReadyTrue else ReadyFalse
      PodStatus(name, status, restarts, Some(nrOfContainersReady), Some(nrOfContainers), Some(ready))
    }
    def apply(name: String): PodStatus = PodStatus(name, Unknown)
    def apply(pod: Pod): PodStatus = {
      // See https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/
      val name                = pod.metadata.name
      val status              = pod.status.getOrElse(Pod.Status())
      val nrOfContainers      = pod.spec.map(_.containers.size).getOrElse(status.containerStatuses.size)
      val nrOfContainersReady = status.containerStatuses.filter(_.ready).size
      val restarts            = status.containerStatuses.map(_.restartCount).sum
      val containerStates     = status.containerStatuses.flatMap(_.state)

      // https://github.com/kubernetes/kubectl/blob/27fa797464ff6684ec591c46c69d5e013998a0d1/pkg/describe/describe.go#L698
      val st = if (pod.metadata.deletionTimestamp.nonEmpty) {
        Terminating
      } else {
        status.phase
          .map {
            case Pod.Phase.Pending   ⇒ getStatusFromContainerStates(containerStates, nrOfContainers)
            case Pod.Phase.Running   ⇒ getStatusFromContainerStates(containerStates, nrOfContainers)
            case Pod.Phase.Succeeded ⇒ Succeeded
            case Pod.Phase.Failed    ⇒ Failed
            case Pod.Phase.Unknown   ⇒ Unknown
          }
          .getOrElse(getStatusFromContainerStates(containerStates, nrOfContainers))
      }
      PodStatus(name, st, restarts, nrOfContainersReady, nrOfContainers)
    }

    private def getStatusFromContainerStates(containerStates: List[Container.State], nrOfContainers: Int) =
      if (containerStates.nonEmpty) {
        // - Running if all containers running;
        // - Terminated if all containers terminated;
        // - first Waiting reason found (ContainerCreating, CrashLoopBackOff, ErrImagePull, ...);
        // - otherwise Pending.
        if (containerStates.collect { case _: Container.Running => 1 }.sum == nrOfContainers) Running
        else if (containerStates.collect { case _: Container.Terminated => 1 }.sum == nrOfContainers) Terminated
        else {
          containerStates
            .collect { case Container.Waiting(Some(reason)) => reason }
            .headOption
            .getOrElse(Pending)
        }
      } else Pending
  }

  /**
   * Status of the pod.
   * ready can be "True", "False" or "Unknown"
   */
  case class PodStatus(
      name: String,
      status: String,
      restarts: Int = 0,
      // Optional for backwards compatibility
      nrOfContainersReady: Option[Int] = None,
      // Optional for backwards compatibility
      nrOfContainers: Option[Int] = None,
      // TODO can this be removed without breaking backwards-compatibility?
      ready: Option[String] = None
  ) {
    def containers      = nrOfContainers.getOrElse(0)
    def containersReady = nrOfContainersReady.getOrElse(0)
    def isReady         = status == PodStatus.Running && containersReady == containers && containers > 0
  }
}
