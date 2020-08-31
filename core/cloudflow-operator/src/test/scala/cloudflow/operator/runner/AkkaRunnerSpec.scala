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

package cloudflow.operator.runner

import cloudflow.blueprint._
import cloudflow.blueprint.deployment.{ PrometheusConfig, StreamletDeployment }
import cloudflow.operator.{ CloudflowApplication, CloudflowApplicationSpecBuilder, TestDeploymentContext }
import com.typesafe.config.ConfigFactory
import org.scalatest._
import play.api.libs.json._
import skuber._

class AkkaRunnerSpec extends WordSpecLike with OptionValues with MustMatchers with GivenWhenThen with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)

  import BlueprintBuilder._
  val appId             = "some-app-id"
  val image             = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629"
  val clusterName       = "cloudflow-strimzi"
  val pvcName           = "my-pvc"
  val namespace         = "test-ns"
  val prometheusJarPath = "/app/prometheus/prometheus.jar"
  val prometheusConfig  = PrometheusConfig("(prometheus rules)")

  "AkkaRunner" should {

    val appId      = "some-app-id"
    val appVersion = "42-abcdef0"
    val agentPaths = Map(CloudflowApplication.PrometheusAgentKey -> "/app/prometheus/prometheus.jar")
    val image      = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629"
    val namespace  = "test-ns"

    val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
    val egress  = randomStreamlet().asEgress[Foo].withServerAttribute

    val ingressRef = ingress.ref("ingress")
    val egressRef  = egress.ref("egress")

    val verifiedBlueprint = Blueprint()
      .define(Vector(ingress, egress))
      .use(ingressRef)
      .use(egressRef)
      .connect(Topic("foos"), ingressRef.out, egressRef.in)
      .verified
      .right
      .value

    val app = CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths))

    val deployment = StreamletDeployment(
      name = appId,
      runtime = "spark",
      image = image,
      streamletName = "spark-streamlet",
      className = "cloudflow.operator.runner.AkkaRunner",
      endpoint = None,
      secretName = "spark-streamlet",
      config = ConfigFactory.empty(),
      portMappings = Map.empty,
      volumeMounts = None,
      replicas = None
    )

    "read from config custom labels and add them to the pod spec" in {

      val crd = AkkaRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
                |kubernetes.pods.pod {
                | labels: {
                |            "key1" : "value1",
                |            "key2" : "value2"
                |          }
                | containers.container {
                |  env = [
                |    {
                |      name = "FOO"
                |      value = "BAR"
                |    }
                |   ]
                |}
                |}
                """.stripMargin.getBytes()
          )
        ),
        namespace = namespace
      )

      crd.spec.get.template.metadata.labels.get("key1") mustBe Some("value1")
      crd.spec.get.template.metadata.labels.get("key2") mustBe Some("value2")

    }
  }
}
