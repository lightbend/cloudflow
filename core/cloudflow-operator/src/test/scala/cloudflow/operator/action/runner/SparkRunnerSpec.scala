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

package cloudflow.operator.action.runner

import akka.datap.crd.App
import cloudflow.blueprint._
import cloudflow.blueprint.deployment.PrometheusConfig
import cloudflow.operator.action._
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.fabric8.kubernetes.api.model.{ EnvVarBuilder, SecretBuilder }
import io.fabric8.kubernetes.client.utils.Serialization
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{ GivenWhenThen, OptionValues }

import scala.util.Try

class SparkRunnerSpec
    extends AnyWordSpecLike
    with OptionValues
    with Matchers
    with GivenWhenThen
    with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)

  import BlueprintBuilder._
  val appId = "some-app-id"
  val image = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629"
  val clusterName = "cloudflow-strimzi"
  val pvcName = "my-pvc"
  val prometheusJarPath = "/app/prometheus/prometheus.jar"
  val prometheusConfig = PrometheusConfig("(prometheus rules)")
  val sparkRunner = new SparkRunner(ctx.sparkRunnerDefaults)

  "SparkRunner" should {

    val appId = "some-app-id"
    val appVersion = "42-abcdef0"
    val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")
    val image = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629"

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

    val app = App.Cr(
      spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
      metadata = CloudflowApplicationSpecBuilder.demoMetadata)

    val deployment = App.Deployment(
      name = appId,
      runtime = "spark",
      image = image,
      streamletName = "spark-streamlet",
      className = "cloudflow.operator.runner.SparkRunner",
      endpoint = None,
      secretName = "spark-streamlet",
      config = Serialization.jsonMapper().readTree("{}"),
      portMappings = Map.empty,
      volumeMounts = Seq(),
      replicas = None)

    val volumes = List(Runner.DownwardApiVolume)

    val volumeMounts = List(Runner.DownwardApiVolumeMount)

    "create a valid SparkApplication CR" in {

      val cr = sparkRunner.resource(deployment = deployment, app = app, configSecret = new SecretBuilder().build())

      cr.metadata.getName mustBe appId
      cr.spec.`type` mustBe "Scala"
      cr.spec.mode mustBe "cluster"
      val imageWithoutRegistry = image.split("/").tail.mkString("/")
      cr.spec.image must include(imageWithoutRegistry)
      cr.spec.mainClass mustBe "cloudflow.runner.Runner"
      cr.spec.volumes mustBe volumes
      cr.spec.driver.volumeMounts mustBe volumeMounts
      cr.spec.executor.volumeMounts mustBe volumeMounts
      cr.getKind mustBe "SparkApplication"
      cr.spec.restartPolicy.`type` mustBe "Always"
      cr.spec.monitoring.exposeDriverMetrics mustBe true
      cr.spec.monitoring.exposeExecutorMetrics mustBe true
      cr.spec.monitoring.prometheus.jmxExporterJar mustBe agentPaths("prometheus")
    }

    "read from config custom labels and add them to the driver pod's spec" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
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
                """.stripMargin))

      cr.spec.driver.labels.get("key1") mustBe Some("value1")
      cr.spec.executor.labels.get("key1") mustBe None
      cr.spec.driver.labels.get("key2") mustBe Some("value2")
      cr.spec.executor.labels.get("key2") mustBe None
    }

    "read from config custom labels and add them to the executor pod's spec" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
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
                """.stripMargin))

      cr.spec.executor.labels.get("key1") mustBe Some("value1")
      cr.spec.driver.labels.get("key1") mustBe None
      cr.spec.executor.labels.get("key2") mustBe Some("value2")
      cr.spec.driver.labels.get("key2") mustBe None
    }

    "read from config custom labels and add them to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
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
                """.stripMargin))

      cr.spec.driver.labels.get("key1") mustBe Some("value1")
      cr.spec.executor.labels.get("key1") mustBe Some("value1")
      cr.spec.driver.labels.get("key2") mustBe Some("value2")
      cr.spec.executor.labels.get("key2") mustBe Some("value2")
    }

    "read from config custom secrets and mount them to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
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
                """.stripMargin))

      cr.spec.volumes.map(v => Try { (v.getName, v.getSecret.getSecretName) }.toOption) must contain allElementsOf List(
        Some("foo", "mysecret"),
        Some("bar", "yoursecret"))

      cr.spec.driver.volumeMounts
        .map(vm => Try { (vm.getName, vm.getMountPath, vm.getReadOnly) }.toOption) must contain allElementsOf List(
        Some("foo", "/etc/my/file", true),
        Some("bar", "/etc/mc/fly", false))

      cr.spec.executor.volumeMounts
        .map(vm => Try { (vm.getName, vm.getMountPath, vm.getReadOnly) }.toOption) must contain allElementsOf List(
        Some("foo", "/etc/my/file", true),
        Some("bar", "/etc/mc/fly", false))

    }

    "read from config custom secrets and mount them DIFFERENTLY to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
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
                """.stripMargin))

      cr.spec.volumes.map(v => Try { (v.getName, v.getSecret.getSecretName) }.toOption) must contain allElementsOf List(
        Some("foo", "mysecret"),
        Some("bar", "yoursecret"))

      cr.spec.driver.volumeMounts
        .map(vm => Try { (vm.getName, vm.getMountPath, vm.getReadOnly) }.toOption) must contain allElementsOf List(
        Some("foo", "/etc/my/file", true))

      cr.spec.executor.volumeMounts
        .map(vm => Try { (vm.getName, vm.getMountPath, vm.getReadOnly) }.toOption) must contain allElementsOf List(
        Some("bar", "/etc/mc/fly", false))

    }

    "read from config DIFFERENT custom labels and add them to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
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
                """.stripMargin))

      cr.spec.driver.env.get mustBe Vector(new EnvVarBuilder().withName("FOO").withValue("BAR").build())
      cr.spec.executor.env.get mustBe Vector(new EnvVarBuilder().withName("FFF").withValue("BBB").build())

      cr.spec.driver.labels.get("key1").get mustBe "value1"
      cr.spec.driver.labels.get("key2").get mustBe "value2"
      cr.spec.executor.labels.get("key3").get mustBe "value3"
      cr.spec.executor.labels.get("key4").get mustBe "value4"
    }

    "read from config custom pvc and mount them to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
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
                """.stripMargin))

      cr.spec.volumes.map(v =>
        Try { (v.getName, v.getPersistentVolumeClaim.getClaimName, v.getPersistentVolumeClaim.getReadOnly) }.toOption) must contain allElementsOf List(
        Some("foo", "myclaim", false))

      cr.spec.driver.volumeMounts
        .map(vm => Try { (vm.getName, vm.getMountPath, vm.getReadOnly) }.toOption) must contain allElementsOf List(
        Some("foo", "/etc/my/file", false))
    }

    "read from config custom pvc and mount them DIFFERENTLY to the driver and executor pods specs" in {

      val cr = sparkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
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
                """.stripMargin))

      cr.spec.volumes.map(v =>
        Try { (v.getName, v.getPersistentVolumeClaim.getClaimName, v.getPersistentVolumeClaim.getReadOnly) }.toOption) must contain allElementsOf List(
        Some("foo", "myclaim1", false),
        Some("bar", "myclaim2", false))

      cr.spec.driver.volumeMounts
        .map(vm => Try { (vm.getName, vm.getMountPath, vm.getReadOnly) }.toOption) must contain allElementsOf List(
        Some("foo", "/etc/my/file", true))

      cr.spec.executor.volumeMounts
        .map(vm => Try { (vm.getName, vm.getMountPath, vm.getReadOnly) }.toOption) must contain allElementsOf List(
        Some("bar", "/etc/mc/fly", false))

    }

    "convert the CR to/from Json" in {
      Serialization.jsonMapper().registerModule(DefaultScalaModule)

      val cr = sparkRunner.resource(deployment = deployment, app = app, configSecret = new SecretBuilder().build())

      val jsonString = Serialization.jsonMapper().writeValueAsString(cr)
      val fromJson = Serialization.jsonMapper().readValue(jsonString, classOf[SparkApp.Cr])

      fromJson.metadata.getName mustBe deployment.name
    }

  }
}
