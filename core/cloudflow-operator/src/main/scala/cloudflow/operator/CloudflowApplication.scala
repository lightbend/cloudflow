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
import cloudflow.blueprint.deployment._

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
      connections: Vector[Connection],
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
  implicit val connectionFmt: Format[Connection]                               = Json.format[Connection]

  implicit val configFmt: Format[Config] = Format[Config](
    Reads(jsValue ⇒
      Try(ConfigFactory.parseString(jsValue.toString)).fold[JsResult[Config]](e ⇒ JsError(e.getMessage), conf ⇒ JsSuccess(conf))
    ),
    Writes(conf ⇒ Json.parse(conf.root().render(ConfigRenderOptions.concise())))
  )
  implicit val savepointFmt: Format[Savepoint]            = Json.format[Savepoint]
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
    shortNames = List("cloudflow"),
    subresources = Some(Subresources().withStatusSubresource)
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[CR]

  val CRD = CustomResourceDefinition[CR]

  def apply(applicationSpec: CloudflowApplication.Spec): CR = {
    CustomResource(
      applicationSpec
    )
  }

  def hash(applicationSpec: CloudflowApplication.Spec): String = {
    val jsonString = Json.stringify(Json.toJson(CloudflowApplication(applicationSpec)))

    def bytesToHexString(bytes: Array[Byte]): String =
      bytes.map("%02x".format(_)).mkString
    val md = MessageDigest.getInstance("MD5")
    md.update(jsonString.getBytes("UTF8"))
    bytesToHexString(md.digest())
  }

  object Status {
    def apply(
        app: CloudflowApplication.CR,
        streamletStatuses: Vector[StreamletStatus] = Vector()
    ): Status =
      Status(
        app.spec.appId,
        app.spec.appVersion,
        streamletStatuses
      )
  }

  case class Status(
      appId: String,
      appVersion: String,
      streamletStatuses: Vector[StreamletStatus]
  ) {

    def updateSpec(spec: CloudflowApplication.Spec) = {
      val newStreamletStatuses =
        streamletStatuses.filter(streamletStatus ⇒ spec.deployments.map(_.streamletName).contains(streamletStatus.streamletName))
      copy(
        appId = spec.appId,
        appVersion = spec.appVersion,
        streamletStatuses = newStreamletStatuses
      )
    }

    def updatePod(streamletName: String, pod: Pod) = {
      val streamletStatus =
        streamletStatuses
          .find(_.streamletName == streamletName)
          .map(_.updatePod(pod))
          .getOrElse(CloudflowApplication.StreamletStatus(streamletName, pod))
      copy(streamletStatuses = streamletStatuses.filterNot(_.streamletName == streamletStatus.streamletName) :+ streamletStatus)
    }

    def deletePod(streamletName: String, pod: Pod) = {
      val streamletStatus =
        streamletStatuses
          .find(_.streamletName == streamletName)
          .map(_.deletePod(pod))
          .getOrElse(CloudflowApplication.StreamletStatus(streamletName, pod))
      copy(streamletStatuses = streamletStatuses.filterNot(_.streamletName == streamletStatus.streamletName) :+ streamletStatus)
    }

    def toAction(app: CloudflowApplication.CR): Action[ObjectResource] =
      Action.updateStatus(app.withStatus(this), editor)
  }

  object StreamletStatus {
    def apply(streamletName: String, pod: Pod): StreamletStatus =
      StreamletStatus(streamletName, Vector(PodStatus(pod)))
    def apply(streamletName: String): StreamletStatus = StreamletStatus(streamletName)
  }

  case class StreamletStatus(streamletName: String, podStatuses: Vector[PodStatus] = Vector()) {
    def updatePod(pod: Pod) = {
      val podStatus = PodStatus(pod)
      copy(podStatuses = podStatuses.filterNot(_.name == podStatus.name) :+ podStatus)
    }
    def deletePod(pod: Pod) = copy(podStatuses = podStatuses.filterNot(_.name == pod.metadata.name))
  }

  object PodStatus {
    def apply(pod: Pod): PodStatus = {
      val name = pod.metadata.name
      pod.status
        .map { status ⇒
          val ready = status.conditions.find(_._type == "Ready").map(_.status).getOrElse("")
          // this is on purpose, so that if this changes, it fails instead of silently providing wrong statuses
          require(status.containerStatuses.size <= 1,
                  s"Expected one container in a pod created by a runner, not '${status.containerStatuses.size}'.")
          val containerStatus = status.containerStatuses.headOption

          val restarts = containerStatus.map(_.restartCount).getOrElse(0)

          val st = containerStatus
            .flatMap(_.state match {
              case Some(Container.Waiting(Some(reason))) if reason == "CrashLoopBackOff" ⇒ Some("CrashLoopBackOff")
              case _ ⇒
                status.phase.map {
                  case Pod.Phase.Pending   ⇒ "ContainerCreating"
                  case Pod.Phase.Running   ⇒ "Running"
                  case Pod.Phase.Succeeded ⇒ "Terminated"
                  case Pod.Phase.Failed    ⇒ "Error"
                  case Pod.Phase.Unknown   ⇒ "Unknown"
                }
            })
            .getOrElse("Unknown")

          PodStatus(name, st, restarts, ready)
        }
        .getOrElse(PodStatus(name, "Unknown", 0, ""))
    }
  }

  /**
   * Status of the pod.
   * ready can be "True", "False" or "Unknown"
   */
  case class PodStatus(name: String, status: String, restarts: Int, ready: String)

}
