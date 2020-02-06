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
package runner

import com.typesafe.config.ConfigFactory
import org.scalatest._
import cloudflow.blueprint._
import cloudflow.blueprint.deployment.{ PrometheusConfig, StreamletDeployment }
import cloudflow.operator.runner.FlinkResource._
import play.api.libs.json._
import skuber.Volume

class FlinkRunnerSpec extends WordSpecLike
  with OptionValues
  with MustMatchers
  with GivenWhenThen
  with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)

  import BlueprintBuilder._
  val appId = "some-app-id"
  val image = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629"
  val clusterName = "cloudflow-strimzi"
  val pvcName = "my-pvc"
  val namespace = "test-ns"
  val prometheusJarPath = "/app/prometheus/prometheus.jar"
  val prometheusConfig = PrometheusConfig("(prometheus rules)")

  "FlinkRunner" should {

    val appId = "some-app-id"
    val appVersion = "42-abcdef0"
    val agentPaths = Map(CloudflowApplication.PrometheusAgentKey -> "/app/prometheus/prometheus.jar")
    val image = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629"
    val namespace = "test-ns"

    val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
    val egress = randomStreamlet().asEgress[Foo].withServerAttribute

    val ingressRef = ingress.ref("ingress")
    val egressRef = egress.ref("egress")

    val verifiedBlueprint = Blueprint()
      .define(Vector(ingress, egress))
      .use(ingressRef)
      .use(egressRef)
      .connect(ingressRef.out, egressRef.in)
      .verified.right.value

    val app = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)

    val deployment = StreamletDeployment(
      name = appId,
      runtime = "flink",
      image = image,
      streamletName = "flink-streamlet",
      className = "cloudflow.operator.runner.FlinkRunner",
      endpoint = None,
      secretName = "flink-streamlet",
      config = ConfigFactory.empty(),
      portMappings = Map.empty,
      volumeMounts = None,
      replicas = None
    )

    "create a valid FlinkApplication CR" in {

      val crd = FlinkRunner.resource(
        deployment = deployment,
        app = app,
        namespace = namespace
      )

      crd.metadata.namespace mustBe namespace
      crd.metadata.name mustBe appId
      val imageWithoutRegistry = image.split("/").tail.mkString("/")
      crd.spec.image must include(imageWithoutRegistry)
      crd.spec.entryClass mustBe "cloudflow.runner.Runner"

      crd.spec.volumes mustBe Vector(
        Volume("config-map-vol", Volume.ConfigMapVolumeSource("configmap-some-app-id")),
        Volume("persistent-storage-vol", Volume.PersistentVolumeClaimRef("some-app-id-pvc")),
        Volume("secret-vol", Volume.Secret("flink-streamlet")),
        Runner.DownwardApiVolume
      )

      crd.spec.volumeMounts mustBe Vector(
        Volume.Mount("persistent-storage-vol", "/mnt/flink/storage"),
        Volume.Mount("secret-vol", "/etc/cloudflow-runner-secret"),
        Volume.Mount("config-map-vol", "/etc/cloudflow-runner"),
        Runner.DownwardApiVolumeMount
      )

      crd.kind mustBe "FlinkApplication"
      crd.spec.serviceAccountName mustBe Name.ofServiceAccount
    }

    "create a valid FlinkApplication CR without resource requests" in {

      val dc = ctx.copy(
        flinkRunnerSettings =
          FlinkRunnerSettings(
            2,
            jobManagerSettings = FlinkJobManagerSettings(1, FlinkPodResourceSettings()),
            taskManagerSettings = FlinkTaskManagerSettings(2, FlinkPodResourceSettings()),
            prometheusRules = "sample rules"
          )
      )
      val crd = FlinkRunner.resource(
        deployment = deployment,
        app = app,
        namespace = namespace
      )(dc)

      crd.spec.serviceAccountName mustBe Name.ofServiceAccount
      crd.spec.jobManagerConfig.resources mustBe None
      crd.spec.taskManagerConfig.resources mustBe None
    }

    "convert the CRD to/from Json" in {

      val cr = FlinkRunner.resource(
        deployment = deployment,
        app = app,
        namespace = namespace
      )

      val jsonString = Json.toJson(cr).toString()
      val fromJson = Json.parse(jsonString).validate[CR]
      fromJson match {
        case err: JsError ⇒ fail(err.toString)
        case _            ⇒
      }
    }
  }
}
