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

      val appId      = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val image      = "image-1"
      val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

      val newApp         = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)
      val cr             = CloudflowApplication(newApp)
      val customResource = Json.fromJson[CloudflowApplication.CR](Json.toJson(cr)).asEither.right.value
      customResource.spec mustBe cr.spec
    }
    // TODO add more tests.
    "report its status as running when all streamlet pods are running and ready" in {
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

      val appId      = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val image      = "image-1"
      val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

      val newApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)
      var status = CloudflowApplication.Status(newApp)
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
  }
  //TODO add mixed pods, to test status from containers
  def mkRunningReadyPod(streamletName: String) =
    Pod(
      metadata = ObjectMeta(s"$streamletName-${java.util.UUID.randomUUID()}"),
      status = Some(
        Pod.Status(
          phase = Some(Pod.Phase.Running),
          conditions = List(Pod.Condition(status = "True")),
          containerStatuses = List(
            Container.Status(
              name = "container-status",
              ready = true,
              restartCount = 0,
              image = "some-image",
              imageID = "some-image-id",
              state = Some(Container.Running(None))
            )
          )
        )
      )
    )
}
