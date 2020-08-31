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
import cloudflow.operator.runner.SparkResource.{ AlwaysRestartPolicy, CR }
import play.api.libs.json._
import skuber._

class SparkRunnerSpec extends WordSpecLike with OptionValues with MustMatchers with GivenWhenThen with TestDeploymentContext {

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

  "SparkRunner" should {

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
      className = "cloudflow.operator.runner.SparkRunner",
      endpoint = None,
      secretName = "spark-streamlet",
      config = ConfigFactory.empty(),
      portMappings = Map.empty,
      volumeMounts = None,
      replicas = None
    )

    val volumes = List(
      Volume("persistent-storage", Volume.PersistentVolumeClaimRef(s"$appId-pvc")),
      Runner.DownwardApiVolume
    )

    val volumeMounts = List(
      Volume.Mount("persistent-storage", "/mnt/spark/storage"),
      Runner.DownwardApiVolumeMount
    )

    "create a valid SparkApplication CR" in {

      val crd = SparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(metadata = ObjectMeta()),
        namespace = namespace
      )

      crd.metadata.namespace mustBe namespace
      crd.metadata.name mustBe appId
      crd.spec.`type` mustBe "Scala"
      crd.spec.mode mustBe "cluster"
      val imageWithoutRegistry = image.split("/").tail.mkString("/")
      crd.spec.image must include(imageWithoutRegistry)
      crd.spec.mainClass mustBe "cloudflow.runner.Runner"
      crd.spec.volumes mustBe volumes
      crd.spec.driver.volumeMounts mustBe volumeMounts
      crd.spec.executor.volumeMounts mustBe volumeMounts
      crd.kind mustBe "SparkApplication"
      crd.spec.restartPolicy mustBe a[AlwaysRestartPolicy]
      crd.spec.monitoring.exposeDriverMetrics mustBe true
      crd.spec.monitoring.exposeExecutorMetrics mustBe true
      crd.spec.monitoring.prometheus.jmxExporterJar mustBe agentPaths(CloudflowApplication.PrometheusAgentKey)
      crd.spec.monitoring.prometheus.configFile mustBe PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath)
    }

    "read from config custom labels and add them to the driver pod's spec" in {

      val crd = SparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
                |kubernetes.pods.driver {
                | labels: {
                |     key1 = value1,
                |     key2 = value2
                | }
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

      crd.spec.driver.labels.get("key1") mustBe Some("value1")
      crd.spec.executor.labels.get("key1") mustBe None
      crd.spec.driver.labels.get("key2") mustBe Some("value2")
      crd.spec.executor.labels.get("key2") mustBe None
    }

    "read from config custom labels and add them to the executor pod's spec" in {

      val crd = SparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
                |kubernetes.pods.executor {
                | labels: {
                |    key1 = value1,
                |    key2 = value2
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

      crd.spec.executor.labels.get("key1") mustBe Some("value1")
      crd.spec.driver.labels.get("key1") mustBe None
      crd.spec.executor.labels.get("key2") mustBe Some("value2")
      crd.spec.driver.labels.get("key2") mustBe None
    }

    "read from config custom labels and add them to the driver and executor pods specs" in {

      val crd = SparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
                |kubernetes.pods.pod {
                | labels: {
                |    key1 = value1,
                |    key2 = value2
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

      crd.spec.driver.labels.get("key1") mustBe Some("value1")
      crd.spec.executor.labels.get("key1") mustBe Some("value1")
      crd.spec.driver.labels.get("key2") mustBe Some("value2")
      crd.spec.executor.labels.get("key2") mustBe Some("value2")
    }

    "read from config DIFFERENT custom labels and add them to the driver and executor pods specs" in {

      val crd = SparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
                |kubernetes.pods {
                |  driver {
                |    labels: {
                |       key1 = value1
                |       key2 = value2
                |    }
                |    containers.container {
                |      env = [
                |       {
                |        name = "FOO"
                |        value = "BAR"
                |        }
                |      ]
                |     }
                |  }
                |  executor {
                |     labels: {
                |        key3 = value3
                |        key4 = value4
                |     }
                |     containers.container {
                |        env = [
                |         {
                |           name = "FFF"
                |           value = "BBB"
                |          }
                |        ]
                |     }
                |  }
                |}
                """.stripMargin.getBytes()
          )
        ),
        namespace = namespace
      )

      crd.spec.driver.env.get mustBe Vector(EnvVar("FOO", EnvVar.StringValue("BAR")))
      crd.spec.executor.env.get mustBe Vector(EnvVar("FFF", EnvVar.StringValue("BBB")))

      crd.spec.driver.labels.get("key1") mustBe Some("value1")
      crd.spec.driver.labels.get("key2") mustBe Some("value2")
      crd.spec.executor.labels.get("key3") mustBe Some("value3")
      crd.spec.executor.labels.get("key4") mustBe Some("value4")
    }

    "convert the CRD to/from Json" in {

      val crd = SparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(metadata = ObjectMeta()),
        namespace = namespace
      )

      val jsonString = Json.toJson(crd).toString()
      val fromJson   = Json.parse(jsonString).validate[CR]
      fromJson match {
        case err: JsError ⇒ fail(err.toString)
        case _            ⇒
      }

    }

  }
}
