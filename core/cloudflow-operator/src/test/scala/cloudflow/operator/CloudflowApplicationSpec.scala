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

import org.scalatest.{ ConfigMap ⇒ _, _ }

import play.api.libs.json.Json
import skuber._
import cloudflow.blueprint._
import cloudflow.operator.runner._
import BlueprintBuilder._

class CloudflowApplicationSpec
    extends WordSpec
    with MustMatchers
    with GivenWhenThen
    with EitherValues
    with OptionValues
    with Inspectors
    with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)

  val appId      = "def-jux-12345"
  val appVersion = "42-abcdef0"
  val image      = "image-1"
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

  "CloudflowApplication.CR" should {
    "convert to Json and back" in {
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress  = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef  = egress.ref("egress")

      val verifiedBlueprint = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(ingressRef.out, egressRef.in)
        .verified
        .right
        .value

      val newApp         = mkApp(verifiedBlueprint)
      val cr             = CloudflowApplication(newApp)
      val customResource = Json.fromJson[CloudflowApplication.CR](Json.toJson(cr)).asEither.right.value
      customResource.spec mustBe cr.spec
    }

    "report its status as Unknown when there are no pod statuses yet" in {
      val status = mkTestStatus()
      status.appStatus mustBe CloudflowApplication.Status.Unknown
    }

    "report its status as Pending when one pod is not ready" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkRunningNotReadyPod("s1"))
      status.appStatus mustBe CloudflowApplication.Status.Pending
    }

    "report its status as Pending when all pods not ready" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkRunningNotReadyPod("s1"))
      status = status.updatePod("s2", mkRunningNotReadyPod("s2"))
      status = status.updatePod("s3", mkRunningNotReadyPod("s3"))
      status.appStatus mustBe CloudflowApplication.Status.Pending
    }

    "report its status as Pending when one pod is waiting" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkWaitingPod("s1", "ContainerCreating"))
      status = status.updatePod("s2", mkRunningReadyPod("s2"))
      status = status.updatePod("s3", mkRunningReadyPod("s3"))
      status.appStatus mustBe CloudflowApplication.Status.Pending
    }

    "report its status as Pending when all pods are waiting" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkWaitingPod("s1", "ContainerCreating"))
      status = status.updatePod("s2", mkWaitingPod("s2", "ContainerCreating"))
      status = status.updatePod("s3", mkWaitingPod("s3", "ContainerCreating"))
      status.appStatus mustBe CloudflowApplication.Status.Pending
    }

    "report its status as CrashLoopBackOff when one pod is in CrashLoopBackOff" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkCrashLoopBackOffPod("s1"))
      status = status.updatePod("s2", mkWaitingPod("s2", "ContainerCreating"))
      status = status.updatePod("s3", mkWaitingPod("s3", "ContainerCreating"))
      status.appStatus mustBe CloudflowApplication.Status.CrashLoopBackOff
    }

    "report its status as CrashLoopBackOff when other podstatuses are terminated" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkCrashLoopBackOffPod("s1"))
      status = status.updatePod("s2", mkTerminatedPod("s2"))
      status = status.updatePod("s3", mkTerminatedPod("s3"))
      status.appStatus mustBe CloudflowApplication.Status.CrashLoopBackOff
    }

    "report its status as Pending when all podstatuses are terminated" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkTerminatedPod("s1"))
      status = status.updatePod("s2", mkTerminatedPod("s2"))
      status = status.updatePod("s3", mkTerminatedPod("s3"))
      status.appStatus mustBe CloudflowApplication.Status.Pending
    }

    "report its status as Pending when not all streamlet pods are running and ready" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkRunningReadyPod("s1"))
      status = status.updatePod("s2", mkRunningReadyPod("s2"))
      status = status.updatePod("s3", mkRunningNotReadyPod("s3"))
      status.appStatus mustBe CloudflowApplication.Status.Pending
    }

    "report its status as Running when all streamlet pods are running and ready" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkRunningReadyPod("s1"))
      status = status.updatePod("s2", mkRunningReadyPod("s2"))
      status = status.updatePod("s3", mkRunningReadyPod("s3"))
      status.appStatus mustBe CloudflowApplication.Status.Running
    }

    "report its status as Pending when all streamlet pods are running and then one pod is deleted" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkRunningReadyPod("s1"))
      status = status.updatePod("s2", mkRunningReadyPod("s2"))
      val pod = mkRunningReadyPod("s3")
      status = status.updatePod("s3", pod)
      status.appStatus mustBe CloudflowApplication.Status.Running
      status = status.deletePod("s3", pod)
      status.appStatus mustBe CloudflowApplication.Status.Pending
    }

    "report its status as Running when all streamlet pods are running, after delete and new running pod" in {
      var status = mkTestStatus()
      status = status.updatePod("s1", mkRunningReadyPod("s1"))
      status = status.updatePod("s2", mkRunningReadyPod("s2"))
      val pod = mkRunningReadyPod("s3")
      status = status.updatePod("s3", pod)
      status.appStatus mustBe CloudflowApplication.Status.Running
      status = status.deletePod("s3", pod)
      status.appStatus mustBe CloudflowApplication.Status.Pending
      status = status.updatePod("s3", mkRunningReadyPod("s3"))
      status.appStatus mustBe CloudflowApplication.Status.Running
    }

    "report its status as Running when all streamlet pods are running and ready in a mixed app" in {
      var status = mkTestStatusMixedApp()
      status = status.updatePod(
        "ingress",
        mkRunningReadyPod("ingress")
      )
      status.appStatus mustBe CloudflowApplication.Status.Pending
      (1 to SparkRunner.DefaultNrOfExecutorInstances + 1).foreach { _ =>
        status = status.updatePod(
          "spark-egress",
          mkRunningReadyPod("spark-egress")
        )
        status.appStatus mustBe CloudflowApplication.Status.Pending
      }

      (1 to FlinkRunner.DefaultReplicas + 1).foreach { _ =>
        status = status.updatePod(
          "flink-egress",
          mkRunningReadyPod("flink-egress")
        )
      }
      status.appStatus mustBe CloudflowApplication.Status.Running
    }

    "report pod status as Running" in {
      val podStatus = CloudflowApplication.PodStatus(mkRunningReadyPod("s1"))
      podStatus.status mustBe CloudflowApplication.PodStatus.Running
      podStatus.containers mustBe 1
      podStatus.containersReady mustBe 1
      podStatus.isReady mustBe true
    }

    "report pod status as Running, not ready" in {
      val podStatus = CloudflowApplication.PodStatus(mkRunningNotReadyPod("s1"))
      podStatus.status mustBe CloudflowApplication.PodStatus.Running
      podStatus.containers mustBe 1
      podStatus.containersReady mustBe 0
      podStatus.isReady mustBe false
    }

    "report pod status as Terminated" in {
      CloudflowApplication.PodStatus(mkTerminatedPod("s1")).status mustBe CloudflowApplication.PodStatus.Terminated
    }
    "report pod status as Terminating" in {
      CloudflowApplication.PodStatus(mkTerminatingPod("s1")).status mustBe CloudflowApplication.PodStatus.Terminating
    }
    "report pod status as Succeeded" in {
      CloudflowApplication.PodStatus(mkSucceededPod("s1")).status mustBe CloudflowApplication.PodStatus.Succeeded
    }
    "report pod status as Failed" in {
      CloudflowApplication.PodStatus(mkFailedPod("s1")).status mustBe CloudflowApplication.PodStatus.Failed
    }
    "report pod status as CrashLoopBackOff" in {
      val podStatus = CloudflowApplication.PodStatus(mkCrashLoopBackOffPod("s1"))
      podStatus.status mustBe CloudflowApplication.PodStatus.CrashLoopBackOff
      podStatus.restarts mustBe 3
    }
  }

  def mkTestStatus() = {
    val ingress = randomStreamlet("akka").asIngress[Foo].withServerAttribute
    val egress1 = randomStreamlet("akka").asEgress[Foo]
    val egress2 = randomStreamlet("akka").asEgress[Foo]

    val ingressRef = ingress.ref("s1")
    val egress1Ref = egress1.ref("s2")
    val egress2Ref = egress2.ref("s3")

    val verifiedBlueprint = Blueprint()
      .define(Vector(ingress, egress1, egress2))
      .use(ingressRef)
      .use(egress1Ref)
      .use(egress2Ref)
      .connect(ingressRef.out, egress1Ref.in)
      .connect(ingressRef.out, egress2Ref.in)
      .verified
      .right
      .value

    val newApp = mkApp(verifiedBlueprint)
    CloudflowApplication.Status(newApp)
  }

  def mkTestStatusMixedApp() = {
    val ingress     = randomStreamlet("akka").asIngress[Foo].withServerAttribute
    val sparkEgress = randomStreamlet("spark").asEgress[Foo]
    val flinkEgress = randomStreamlet("flink").asEgress[Foo]

    val ingressRef     = ingress.ref("ingress")
    val sparkEgressRef = sparkEgress.ref("spark-egress")
    val flinkEgressRef = flinkEgress.ref("flink-egress")

    val verifiedBlueprint = Blueprint()
      .define(Vector(ingress, sparkEgress, flinkEgress))
      .use(ingressRef)
      .use(sparkEgressRef)
      .use(flinkEgressRef)
      .connect(ingressRef.out, sparkEgressRef.in)
      .connect(ingressRef.out, flinkEgressRef.in)
      .verified
      .right
      .value

    val newApp = mkApp(verifiedBlueprint)
    CloudflowApplication.Status(newApp)
  }

  def mkApp(verifiedBlueprint: VerifiedBlueprint) =
    CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)

  def mkRunningReadyPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(Pod.Phase.Running),
      containerStatuses = List(
        mkContainerStatus(ready = true)
      )
    )

  def mkRunningNotReadyPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(Pod.Phase.Running),
      containerStatuses = List(
        mkContainerStatus(ready = false)
      )
    )
  def mkFailedPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(Pod.Phase.Failed),
      containerStatuses = List(
        mkContainerStatus(ready = false)
      )
    )
  def mkSucceededPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(Pod.Phase.Succeeded),
      containerStatuses = List(
        mkContainerStatus(ready = false)
      )
    )

  def mkWaitingPod(streamletName: String, reason: String) =
    mkPod(
      streamletName,
      Some(Pod.Phase.Pending),
      containerStatuses = List(
        mkContainerStatus(state = Some(Container.Waiting(Some(reason))), ready = false)
      )
    )

  def mkTerminatingPod(streamletName: String) =
    mkPod(
      streamletName = streamletName,
      deletionTimestamp = Some(java.time.ZonedDateTime.now())
    )

  def mkTerminatedPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(Pod.Phase.Pending),
      containerStatuses = List(
        mkContainerStatus(state = Some(Container.Terminated(0)), ready = false)
      )
    )

  def mkCrashLoopBackOffPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(Pod.Phase.Pending),
      containerStatuses = List(
        mkContainerStatus(
          state = Some(Container.Waiting(Some(CloudflowApplication.PodStatus.CrashLoopBackOff))),
          ready = false,
          restartCount = 3
        )
      )
    )

  def mkPod(
      streamletName: String,
      phase: Option[Pod.Phase.Phase] = None,
      containerStatuses: List[Container.Status] = List(),
      deletionTimestamp: Option[skuber.Timestamp] = None
  ) = Pod(
    metadata = ObjectMeta(name = s"$streamletName-${java.util.UUID.randomUUID()}", deletionTimestamp = deletionTimestamp),
    status = Some(
      Pod.Status(
        phase = phase,
        conditions = List(),
        containerStatuses = containerStatuses
      )
    )
  )
  def mkContainerStatus(
      state: Option[Container.State] = Some(Container.Running(None)),
      ready: Boolean = false,
      restartCount: Int = 0
  ) = Container.Status(
    name = "container-status",
    ready = ready,
    restartCount = restartCount,
    image = "some-image",
    imageID = "some-image-id",
    state = state
  )
}
