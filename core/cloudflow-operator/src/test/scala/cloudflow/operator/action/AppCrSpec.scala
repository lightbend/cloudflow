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
import cloudflow.blueprint.BlueprintBuilder._
import cloudflow.blueprint._
import cloudflow.operator.action.runner._
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.utils.Serialization
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{ BeforeAndAfterAll, EitherValues, GivenWhenThen, Inspectors, OptionValues }

import scala.jdk.CollectionConverters._

class AppCrSpec
    extends AnyWordSpec
    with Matchers
    with GivenWhenThen
    with EitherValues
    with OptionValues
    with Inspectors
    with BeforeAndAfterAll
    with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)

  val appId = "def-jux-12345"
  val appVersion = "42-abcdef0"
  val image = "image-1"
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

  override def beforeAll() = {
    Serialization.jsonMapper().registerModule(DefaultScalaModule)
    super.beforeAll()
  }

  "CloudflowApplication.CR" should {
    "convert to Json and back" in {
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef = egress.ref("egress")

      val verifiedBlueprint = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(Topic("foos"), ingressRef.out, egressRef.in)
        .verified
        .right
        .value

      val newApp = mkApp(verifiedBlueprint)
      val cr = App.Cr(spec = newApp, metadata = null)
      val customResource =
        Serialization.jsonMapper().readValue(Serialization.jsonMapper().writeValueAsString(cr), classOf[App.Cr])
      customResource.spec mustBe cr.spec
    }

    "report its status as Pending when there are no pod statuses yet" in {
      val status = mkTestStatus()
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
    }

    "report its status as Pending when one pod is not ready" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkRunningNotReadyPod("s1"))
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
    }

    "report its status as Pending when all pods not ready" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkRunningNotReadyPod("s1"))
      status = CloudflowStatus.updatePod(status)("s2", mkRunningNotReadyPod("s2"))
      status = CloudflowStatus.updatePod(status)("s3", mkRunningNotReadyPod("s3"))
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
    }

    "report its status as Pending when one pod is waiting" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkWaitingPod("s1", "ContainerCreating"))
      status = CloudflowStatus.updatePod(status)("s2", mkRunningNotReadyPod("s2"))
      status = CloudflowStatus.updatePod(status)("s3", mkRunningNotReadyPod("s3"))
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
    }

    "report its status as Pending when all pods are waiting" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkWaitingPod("s1", "ContainerCreating"))
      status = CloudflowStatus.updatePod(status)("s2", mkWaitingPod("s2", "ContainerCreating"))
      status = CloudflowStatus.updatePod(status)("s3", mkWaitingPod("s3", "ContainerCreating"))
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
    }

    "report its status as CrashLoopBackOff when one pod is in CrashLoopBackOff" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkCrashLoopBackOffPod("s1"))
      status = CloudflowStatus.updatePod(status)("s2", mkWaitingPod("s2", "ContainerCreating"))
      status = CloudflowStatus.updatePod(status)("s3", mkWaitingPod("s3", "ContainerCreating"))
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.CrashLoopBackOff
    }

    "report its status as CrashLoopBackOff when other podstatuses are terminated" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkCrashLoopBackOffPod("s1"))
      status = CloudflowStatus.updatePod(status)("s2", mkTerminatedPod("s2"))
      status = CloudflowStatus.updatePod(status)("s3", mkTerminatedPod("s3"))
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.CrashLoopBackOff
    }

    "report its status as Pending when all podstatuses are terminated" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkTerminatedPod("s1"))
      status = CloudflowStatus.updatePod(status)("s2", mkTerminatedPod("s2"))
      status = CloudflowStatus.updatePod(status)("s3", mkTerminatedPod("s3"))
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
    }

    "report its status as Pending when not all streamlet pods are running and ready" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkRunningReadyPod("s1"))
      status = CloudflowStatus.updatePod(status)("s2", mkRunningReadyPod("s2"))
      status = CloudflowStatus.updatePod(status)("s3", mkTerminatedPod("s3"))
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
    }

    "report its status as Running when all streamlet pods are running and ready" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkRunningReadyPod("s1"))
      status = CloudflowStatus.updatePod(status)("s2", mkRunningReadyPod("s2"))
      status = CloudflowStatus.updatePod(status)("s3", mkRunningReadyPod("s3"))
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Running
    }

    "report its status as Pending when all streamlet pods are running and then one pod is deleted" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkRunningReadyPod("s1"))
      status = CloudflowStatus.updatePod(status)("s2", mkRunningReadyPod("s2"))
      val pod = mkRunningReadyPod("s3")
      status = CloudflowStatus.updatePod(status)("s3", pod)
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Running
      status = CloudflowStatus.deletePod(status)("s3", pod)
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
    }

    "report its status as Running when all streamlet pods are running, after delete and new running pod" in {
      var status = mkTestStatus()
      status = CloudflowStatus.updatePod(status)("s1", mkRunningReadyPod("s1"))
      status = CloudflowStatus.updatePod(status)("s2", mkRunningReadyPod("s2"))
      val pod = mkRunningReadyPod("s3")
      status = CloudflowStatus.updatePod(status)("s3", pod)
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Running
      status = CloudflowStatus.deletePod(status)("s3", pod)
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
      status = CloudflowStatus.updatePod(status)("s3", mkRunningReadyPod("s3"))
      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Running
    }

    "report its status as Running when all streamlet pods are running and ready in a mixed app" in {
      var status = mkTestStatusMixedApp()
      status = CloudflowStatus.updatePod(status)("ingress", mkRunningReadyPod("ingress"))

      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
      (1 to SparkRunner.DefaultNrOfExecutorInstances + 1).foreach { _ =>
        status = CloudflowStatus.updatePod(status)("spark-egress", mkRunningReadyPod("spark-egress"))
        CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
      }

      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
      (1 to FlinkRunner.DefaultTaskManagerReplicas + 1).foreach { _ =>
        status = CloudflowStatus.updatePod(status)("flink-egress", mkRunningReadyPod("flink-egress"))
      }

      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Running
    }

    "report its status as Running when all streamlet pods but flink are running and ready in a mixed app" in {
      var status = mkTestStatusExternalFlinkApp()
      status = CloudflowStatus.updatePod(status)("ingress", mkRunningReadyPod("ingress"))

      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
      (1 to SparkRunner.DefaultNrOfExecutorInstances).foreach { _ =>
        status = CloudflowStatus.updatePod(status)("spark-egress", mkRunningReadyPod("spark-egress"))
        CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
      }
      status = CloudflowStatus.updatePod(status)("spark-egress", mkRunningReadyPod("spark-egress"))

      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Running
    }

    "report its status as Running when all streamlet pods but spark are running and ready in a mixed app" in {
      var status = mkTestStatusExternalSparkApp()
      status = CloudflowStatus.updatePod(status)("ingress", mkRunningReadyPod("ingress"))

      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
      (1 to FlinkRunner.DefaultTaskManagerReplicas).foreach { _ =>
        status = CloudflowStatus.updatePod(status)("flink-egress", mkRunningReadyPod("flink-egress"))
        CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Pending
      }
      status = CloudflowStatus.updatePod(status)("flink-egress", mkRunningReadyPod("flink-egress"))

      CloudflowStatus.aggregatedStatus(status) mustBe CloudflowStatus.Status.Running
    }

    "report pod status as Running" in {
      val podStatus = CloudflowStatus.fromPod(mkRunningReadyPod("s1"))
      podStatus.status mustBe CloudflowStatus.PodStatus.Running
      podStatus.nrOfContainers mustBe 1
      podStatus.nrOfContainersReady mustBe 1
      podStatus.ready.toLowerCase mustBe "true"
    }

    "report pod status as Running, not ready" in {
      val podStatus = CloudflowStatus.fromPod(mkRunningNotReadyPod("s1"))
      podStatus.status mustBe CloudflowStatus.PodStatus.Running
      podStatus.nrOfContainers mustBe 1
      podStatus.nrOfContainersReady mustBe 0
      podStatus.ready.toLowerCase mustBe "false"
    }

    "report pod status as Terminated" in {
      CloudflowStatus.fromPod(mkTerminatedPod("s1")).status mustBe CloudflowStatus.PodStatus.Terminated
    }
    "report pod status as Terminating" in {
      CloudflowStatus.fromPod(mkTerminatingPod("s1")).status mustBe CloudflowStatus.PodStatus.Terminating
    }
    "report pod status as Succeeded" in {
      CloudflowStatus.fromPod(mkSucceededPod("s1")).status mustBe CloudflowStatus.PodStatus.Succeeded
    }
    "report pod status as Failed" in {
      CloudflowStatus.fromPod(mkFailedPod("s1")).status mustBe CloudflowStatus.PodStatus.Failed
    }
    "report pod status as CrashLoopBackOff" in {
      val podStatus = CloudflowStatus.fromPod(mkCrashLoopBackOffPod("s1"))
      podStatus.status mustBe CloudflowStatus.PodStatus.CrashLoopBackOff
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
      .connect(Topic("foos1"), ingressRef.out, egress1Ref.in)
      .connect(Topic("foos2"), egress2Ref.in)
      .verified
      .right
      .value

    val newApp = mkApp(verifiedBlueprint)
    CloudflowStatus.freshStatus(newApp, runners)
  }

  def mkTestStatusMixedApp() = {
    val ingress = randomStreamlet("akka").asIngress[Foo].withServerAttribute
    val sparkEgress = randomStreamlet("spark").asEgress[Foo]
    val flinkEgress = randomStreamlet("flink").asEgress[Foo]

    val ingressRef = ingress.ref("ingress")
    val sparkEgressRef = sparkEgress.ref("spark-egress")
    val flinkEgressRef = flinkEgress.ref("flink-egress")

    val verifiedBlueprint = Blueprint()
      .define(Vector(ingress, sparkEgress, flinkEgress))
      .use(ingressRef)
      .use(sparkEgressRef)
      .use(flinkEgressRef)
      .connect(Topic("foos1"), ingressRef.out, sparkEgressRef.in)
      .connect(Topic("foos2"), flinkEgressRef.in)
      .verified
      .right
      .value

    val newApp = mkApp(verifiedBlueprint)
    CloudflowStatus.freshStatus(newApp, runners)
  }

  def mkTestStatusExternalFlinkApp() = {
    val ingress = randomStreamlet("akka").asIngress[Foo].withServerAttribute
    val sparkEgress = randomStreamlet("spark").asEgress[Foo]
    val flinkEgress = randomStreamlet("flink").asEgress[Foo]

    val ingressRef = ingress.ref("ingress")
    val sparkEgressRef = sparkEgress.ref("spark-egress")
    val flinkEgressRef = flinkEgress.ref("flink-egress")

    val verifiedBlueprint = Blueprint()
      .define(Vector(ingress, sparkEgress, flinkEgress))
      .use(ingressRef)
      .use(sparkEgressRef)
      .use(flinkEgressRef)
      .connect(Topic("foos1"), ingressRef.out, sparkEgressRef.in)
      .connect(Topic("foos2"), flinkEgressRef.in)
      .verified
      .right
      .value

    val newApp = mkApp(verifiedBlueprint)
    CloudflowStatus.freshStatus(newApp, runners.filter { case (k, _) => k != "flink" })
  }

  def mkTestStatusExternalSparkApp() = {
    val ingress = randomStreamlet("akka").asIngress[Foo].withServerAttribute
    val sparkEgress = randomStreamlet("spark").asEgress[Foo]
    val flinkEgress = randomStreamlet("flink").asEgress[Foo]

    val ingressRef = ingress.ref("ingress")
    val sparkEgressRef = sparkEgress.ref("spark-egress")
    val flinkEgressRef = flinkEgress.ref("flink-egress")

    val verifiedBlueprint = Blueprint()
      .define(Vector(ingress, sparkEgress, flinkEgress))
      .use(ingressRef)
      .use(sparkEgressRef)
      .use(flinkEgressRef)
      .connect(Topic("foos1"), ingressRef.out, sparkEgressRef.in)
      .connect(Topic("foos2"), flinkEgressRef.in)
      .verified
      .right
      .value

    val newApp = mkApp(verifiedBlueprint)
    CloudflowStatus.freshStatus(newApp, runners.filter { case (k, _) => k != "spark" })
  }

  def mkApp(verifiedBlueprint: VerifiedBlueprint) =
    CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)

  def mkRunningReadyPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(CloudflowStatus.PodStatus.Running),
      containerStatuses = List(mkContainerStatus(ready = true)))

  def mkRunningNotReadyPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(CloudflowStatus.PodStatus.Running),
      containerStatuses = List(mkContainerStatus(ready = false)))

  def mkFailedPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(CloudflowStatus.PodStatus.Failed),
      containerStatuses = List(mkContainerStatus(ready = false)))
  def mkSucceededPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(CloudflowStatus.PodStatus.Succeeded),
      containerStatuses = List(mkContainerStatus(ready = false)))
  def mkWaitingPod(streamletName: String, reason: String) =
    mkPod(
      streamletName,
      Some(CloudflowStatus.PodStatus.Pending),
      containerStatuses = List(
        mkContainerStatus(
          state = new ContainerStateBuilder().withNewWaiting().withReason(reason).endWaiting().build(),
          ready = false)))

  def mkTerminatingPod(streamletName: String) =
    mkPod(streamletName = streamletName, deletionTimestamp = Some(java.time.ZonedDateTime.now().toString))

  def mkTerminatedPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(CloudflowStatus.PodStatus.Pending),
      containerStatuses = List(
        mkContainerStatus(
          state = new ContainerStateBuilder().withNewTerminated().withExitCode(0).endTerminated().build(),
          ready = false)))

  def mkCrashLoopBackOffPod(streamletName: String) =
    mkPod(
      streamletName,
      Some(CloudflowStatus.PodStatus.Pending),
      containerStatuses = List(
        mkContainerStatus(
          state = new ContainerStateBuilder()
            .withNewWaiting()
            .withNewReason(CloudflowStatus.PodStatus.CrashLoopBackOff)
            .endWaiting()
            .build(),
          ready = false,
          restartCount = 3)))

  def mkPod(
      streamletName: String,
      phase: Option[String] = None,
      containerStatuses: List[ContainerStatus] = List(),
      deletionTimestamp: Option[String] = None) = {
    val metadataB = new ObjectMetaBuilder()
      .withName(s"$streamletName-${java.util.UUID.randomUUID()}")

    val metadata = (deletionTimestamp match {
      case Some(timestamp) => metadataB.withDeletionTimestamp(timestamp)
      case _               => metadataB
    }).build()

    val podStatusB = new PodStatusBuilder()

    val status = (phase match {
      case Some(p) => podStatusB.withPhase(p)
      case _       => podStatusB
    }).withContainerStatuses(containerStatuses.asJava)
      .build()

    new PodBuilder()
      .withMetadata(metadata)
      .withStatus(status)
      .build()
  }

  def mkContainerStatus(state: ContainerState = {
    new ContainerStateBuilder()
      .withNewRunning(java.time.ZonedDateTime.now().toString)
      .build()
  }, ready: Boolean = true, restartCount: Int = 0) =
    new ContainerStatusBuilder()
      .withName("container-status")
      .withReady(ready)
      .withState(state)
      .withRestartCount(restartCount)
      .withImage("some-image")
      .withImageID("some-image-id")
      .build()

}
