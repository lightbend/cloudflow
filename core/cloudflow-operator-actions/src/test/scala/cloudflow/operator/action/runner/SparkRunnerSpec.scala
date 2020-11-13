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

package cloudflow.operator.action.runner

import com.typesafe.config.ConfigFactory
import org.scalatest._
import play.api.libs.json._
import skuber._

import cloudflow.blueprint._
import cloudflow.blueprint.deployment.{ PrometheusConfig, StreamletDeployment }
import cloudflow.operator.action._
import cloudflow.operator.action.runner.SparkResource.{ AlwaysRestartPolicy, CR }

class SparkRunnerSpec extends WordSpecLike with OptionValues with MustMatchers with GivenWhenThen with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)

  import BlueprintBuilder._
  val appId             = "some-app-id"
  val image             = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629"
  val clusterName       = "cloudflow-strimzi"
  val pvcName           = "my-pvc"
  val prometheusJarPath = "/app/prometheus/prometheus.jar"
  val prometheusConfig  = PrometheusConfig("(prometheus rules)")
  val sparkRunner       = new SparkRunner(ctx.sparkRunnerDefaults)

  "SparkRunner" should {

    val appId      = "some-app-id"
    val appVersion = "42-abcdef0"
    val agentPaths = Map(CloudflowApplication.PrometheusAgentKey -> "/app/prometheus/prometheus.jar")
    val image      = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629"

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
      Runner.DownwardApiVolume
    )

    val volumeMounts = List(
      Runner.DownwardApiVolumeMount
    )

    "create a valid SparkApplication CR" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(metadata = ObjectMeta())
      )

      cr.metadata.name mustBe appId
      cr.spec.`type` mustBe "Scala"
      cr.spec.mode mustBe "cluster"
      val imageWithoutRegistry = image.split("/").tail.mkString("/")
      cr.spec.image must include(imageWithoutRegistry)
      cr.spec.mainClass mustBe "cloudflow.runner.Runner"
      cr.spec.volumes mustBe volumes
      cr.spec.driver.volumeMounts mustBe volumeMounts
      cr.spec.executor.volumeMounts mustBe volumeMounts
      cr.kind mustBe "SparkApplication"
      cr.spec.restartPolicy mustBe a[AlwaysRestartPolicy]
      cr.spec.monitoring.exposeDriverMetrics mustBe true
      cr.spec.monitoring.exposeExecutorMetrics mustBe true
      cr.spec.monitoring.prometheus.jmxExporterJar mustBe agentPaths(CloudflowApplication.PrometheusAgentKey)
      cr.spec.monitoring.prometheus.configFile mustBe PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath)
    }

    "read from config custom labels and add them to the driver pod's spec" in {

      val cr = sparkRunner.resource(
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
        )
      )

      cr.spec.driver.labels.get("key1") mustBe Some("value1")
      cr.spec.executor.labels.get("key1") mustBe None
      cr.spec.driver.labels.get("key2") mustBe Some("value2")
      cr.spec.executor.labels.get("key2") mustBe None
    }

    "read from config custom labels and add them to the executor pod's spec" in {

      val cr = sparkRunner.resource(
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
        )
      )

      cr.spec.executor.labels.get("key1") mustBe Some("value1")
      cr.spec.driver.labels.get("key1") mustBe None
      cr.spec.executor.labels.get("key2") mustBe Some("value2")
      cr.spec.driver.labels.get("key2") mustBe None
    }

    "read from config custom labels and add them to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
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
        )
      )

      cr.spec.driver.labels.get("key1") mustBe Some("value1")
      cr.spec.executor.labels.get("key1") mustBe Some("value1")
      cr.spec.driver.labels.get("key2") mustBe Some("value2")
      cr.spec.executor.labels.get("key2") mustBe Some("value2")
    }

    "read from config custom secrets and mount them to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
                |kubernetes.pods.pod {
                |   volumes {
                |     foo {
                |       secret {
                |         name = mysecret
                |       }
                |     }
                |     bar {
                |       secret {
                |         name = yoursecret
                |       }
                |     }
                |   }
                |   containers.container {
                |     volume-mounts {
                |       foo {
                |         mount-path = "/etc/my/file"
                |         read-only = true
                |       }
                |       bar {
                |         mount-path = "/etc/mc/fly"
                |         read-only =  false
                |       }
                |     }
                |   }
                |}
                """.stripMargin.getBytes()
          )
        )
      )

      cr.spec.volumes must contain allElementsOf List(
        Volume("foo", Volume.Secret(secretName = "mysecret")),
        Volume("bar", Volume.Secret(secretName = "yoursecret"))
      )

      cr.spec.driver.volumeMounts must contain allElementsOf List(
        Volume.Mount("foo", "/etc/my/file", true),
        Volume.Mount("bar", "/etc/mc/fly", false)
      )

      cr.spec.executor.volumeMounts must contain allElementsOf List(
        Volume.Mount("foo", "/etc/my/file", true),
        Volume.Mount("bar", "/etc/mc/fly", false)
      )

    }

    "read from config custom secrets and mount them DIFFERENTLY to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
                |kubernetes.pods {
                | pod {
                |   volumes {
                |     foo {
                |       secret {
                |         name = mysecret
                |       }
                |     }
                |     bar {
                |       secret {
                |         name = yoursecret
                |       }
                |     }
                |   }
                | }
                | driver {
                |   containers.container {
                |     volume-mounts {
                |       foo {
                |         mount-path = "/etc/my/file"
                |         read-only = true
                |       }
                |     }
                |   }
                | }
                | executor {
                |   containers.container {
                |     volume-mounts {
                |       bar {
                |         mount-path = "/etc/mc/fly"
                |         read-only =  false
                |       }
                |     }
                |   }
                | }
                |}
                """.stripMargin.getBytes()
          )
        )
      )

      cr.spec.volumes must contain allElementsOf List(
        Volume("foo", Volume.Secret(secretName = "mysecret")),
        Volume("bar", Volume.Secret(secretName = "yoursecret"))
      )

      cr.spec.driver.volumeMounts must contain allElementsOf List(
        Volume.Mount("foo", "/etc/my/file", true)
      )

      cr.spec.executor.volumeMounts must contain allElementsOf List(
        Volume.Mount("bar", "/etc/mc/fly", false)
      )

    }

    "read from config DIFFERENT custom labels and add them to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
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
        )
      )

      cr.spec.driver.env.get mustBe Vector(EnvVar("FOO", EnvVar.StringValue("BAR")))
      cr.spec.executor.env.get mustBe Vector(EnvVar("FFF", EnvVar.StringValue("BBB")))

      cr.spec.driver.labels.get("key1") mustBe Some("value1")
      cr.spec.driver.labels.get("key2") mustBe Some("value2")
      cr.spec.executor.labels.get("key3") mustBe Some("value3")
      cr.spec.executor.labels.get("key4") mustBe Some("value4")
    }

    "read from config custom pvc and mount them to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
                |kubernetes.pods.pod {
                |   volumes {
                |     foo {
                |       pvc {
                |         name = myclaim
                |         read-only = false
                |       }
                |     }
                |   }
                |   containers.container {
                |     volume-mounts {
                |       foo {
                |         mount-path = "/etc/my/file"
                |         read-only = false
                |       }
                |     }
                |   }
                |}
                """.stripMargin.getBytes()
          )
        )
      )

      cr.spec.volumes must contain allElementsOf List(
        Volume("foo", Volume.PersistentVolumeClaimRef(claimName = "myclaim", readOnly = false))
      )

      cr.spec.driver.volumeMounts must contain allElementsOf List(
        Volume.Mount("foo", "/etc/my/file", false)
      )
    }

    "read from config custom pvc and mount them DIFFERENTLY to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
                |kubernetes.pods {
                | pod {
                |   volumes {
                |     foo {
                |       pvc {
                |         name = myclaim1
                |         read-only = false
                |       }
                |     }
                |     bar {
                |       pvc {
                |         name = myclaim2
                |         read-only = false
                |       }
                |     }
                |   }
                | }
                | driver {
                |   containers.container {
                |     volume-mounts {
                |       foo {
                |         mount-path = "/etc/my/file"
                |         read-only = true
                |       }
                |     }
                |   }
                | }
                | executor {
                |   containers.container {
                |     volume-mounts {
                |       bar {
                |         mount-path = "/etc/mc/fly"
                |         read-only =  false
                |       }
                |     }
                |   }
                | }
                |}
                """.stripMargin.getBytes()
          )
        )
      )

      cr.spec.volumes must contain allElementsOf List(
        Volume("foo", Volume.PersistentVolumeClaimRef(claimName = "myclaim1", readOnly = false)),
        Volume("bar", Volume.PersistentVolumeClaimRef(claimName = "myclaim2", readOnly = false))
      )

      cr.spec.driver.volumeMounts must contain allElementsOf List(
        Volume.Mount("foo", "/etc/my/file", true)
      )
      cr.spec.executor.volumeMounts must contain allElementsOf List(
        Volume.Mount("bar", "/etc/mc/fly", false)
      )

    }

    "convert the CR to/from Json" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(metadata = ObjectMeta())
      )

      val jsonString = Json.toJson(cr).toString()
      val fromJson   = Json.parse(jsonString).validate[CR]
      fromJson match {
        case err: JsError => fail(err.toString)
        case _            =>
      }

    }

  }
}
