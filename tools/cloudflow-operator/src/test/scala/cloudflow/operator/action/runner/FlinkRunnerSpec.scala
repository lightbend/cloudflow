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
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.utils.Serialization
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{ GivenWhenThen, OptionValues }

import scala.jdk.CollectionConverters._
import scala.util.Try

class FlinkRunnerSpec
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
  val flinkRunner = new FlinkRunner(ctx.flinkRunnerDefaults)

  "FlinkRunner" should {

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
      runtime = "flink",
      image = image,
      streamletName = "flink-streamlet",
      className = "cloudflow.operator.runner.FlinkRunner",
      endpoint = None,
      secretName = "flink-streamlet",
      config = Serialization.jsonMapper().readTree("{}"),
      portMappings = Map.empty,
      volumeMounts = Seq(),
      replicas = None)

    "read from config environment variables and resource requirements and add them to the jobmanager and taskmanager pods specs" in {

      val cr = flinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
              |kubernetes.pods.pod.containers.container {
              |  env = [
              |    { 
              |       name = "JAVA_OPTS"
              |       value = "-XX:MaxRAMPercentage=40.0"
              |    },{
              |       name = "FOO"
              |       value = "BAR"
              |    }
              |   ]
              |  resources {
              |    requests {
              |      memory = "512M"
              |    }
              |    limits {
              |      memory = "1024M"
              |    }
              |  }
              |}
              |""".stripMargin))

      cr.getMetadata.getName mustBe appId
      val imageWithoutRegistry = image.split("/").tail.mkString("/")
      cr.spec.image must include(imageWithoutRegistry)
      cr.spec.entryClass mustBe "cloudflow.runner.Runner"

      cr.spec.volumes mustBe Vector(
        new VolumeBuilder()
          .withName("config-map-vol")
          .withConfigMap(new ConfigMapVolumeSourceBuilder().withName("configmap-some-app-id").build())
          .build(),
        new VolumeBuilder()
          .withName("secret-vol")
          .withSecret(new SecretVolumeSourceBuilder().withSecretName("flink-streamlet").build())
          .build(),
        Runner.DownwardApiVolume)

      cr.spec.jobManagerConfig.envConfig.get.env.get mustBe Vector(
        new EnvVarBuilder().withName("FOO").withValue("BAR").build())
      cr.spec.taskManagerConfig.envConfig.get.env.get mustBe Vector(
        new EnvVarBuilder().withName("FOO").withValue("BAR").build())

      cr.spec.jobManagerConfig.resources.get.getRequests.asScala mustBe Map(
        "cpu" -> ctx.flinkRunnerDefaults.jobManagerDefaults.resources.cpuRequest.get,
        "memory" -> (Quantity.parse("512M")))
      cr.spec.taskManagerConfig.resources.get.getRequests.asScala mustBe Map(
        "cpu" -> ctx.flinkRunnerDefaults.taskManagerDefaults.resources.cpuRequest.get,
        "memory" -> Quantity.parse("512M"))
      cr.spec.jobManagerConfig.resources.get.getLimits.asScala mustBe Map(
        "cpu" -> ctx.flinkRunnerDefaults.jobManagerDefaults.resources.cpuLimit.get,
        "memory" -> Quantity.parse("1024M"))
      cr.spec.taskManagerConfig.resources.get.getLimits.asScala mustBe Map(
        "cpu" -> ctx.flinkRunnerDefaults.taskManagerDefaults.resources.cpuLimit.get,
        "memory" -> Quantity.parse("1024M"))
    }

    "read from config custom labels and add them to the jobmanager and taskmanager pods specs" in {

      val cr = flinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
                  |kubernetes.pods.pod {
                  | labels: {
                  |     "key1" : "value1",
                  |     "key2" : "value2"
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

      cr.spec.jobManagerConfig.envConfig.get.env.get mustBe Vector(
        new EnvVarBuilder().withName("FOO").withValue("BAR").build())
      cr.spec.taskManagerConfig.envConfig.get.env.get mustBe Vector(
        new EnvVarBuilder().withName("FOO").withValue("BAR").build())

      cr.metadata.getLabels.get("key1") mustBe "value1"
      cr.metadata.getLabels.get("key2") mustBe "value2"
    }

    "read from config custom secrets and mount them in jobmanager and taskmanager pods" in {

      val cr = flinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
                |kubernetes.pods.pod {
                |   volumes {
                |     foo {
                |       secret {
                |         name = mysecret
                |       }
                |     },
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
                |       },
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

      cr.spec.volumeMounts
        .map(vm => (vm.getName, vm.getMountPath, vm.getReadOnly))
        .toList must contain allElementsOf List(("foo", "/etc/my/file", true), ("bar", "/etc/mc/fly", false))

    }

    "read values from pod configuration key JAVA_OPTS and put it in Flink conf in env.java.opts" in {

      val cr = flinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
              |kubernetes.pods.pod.containers.container {
              |  env = [
              |    { name = "JAVA_OPTS"
              |      value = "-XX:MaxRAMPercentage=40.0"
              |    }
              |  ]
              |}
              |""".stripMargin))

      cr.spec.flinkConfig.get("env.java.opts") mustBe Some("-XX:MaxRAMPercentage=40.0")
    }

    "configure env.java.opts from runtime Flink conf, overriding what is provided as JAVA_OPTS value in the pod configuration" in {

      val cr = flinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret(
          """
              |kubernetes.pods.pod.containers.container {
              |  env = [
              |    { name = "JAVA_OPTS"
              |      value = "-XX:MaxRAMPercentage=40.0"
              |    }
              |   ]
              |}
              |        """.stripMargin,
          """flink.env.java.opts = "-XX:-DisableExplicitGC""""))

      cr.spec.flinkConfig.get("env.java.opts") mustBe Some("-XX:-DisableExplicitGC")
    }

    "create a valid FlinkApplication CR" in {

      val cr = flinkRunner.resource(deployment = deployment, app = app, configSecret = new SecretBuilder().build())

      cr.metadata.getName mustBe appId
      val imageWithoutRegistry = image.split("/").tail.mkString("/")
      cr.spec.image must include(imageWithoutRegistry)
      cr.spec.entryClass mustBe "cloudflow.runner.Runner"

      cr.spec.volumes mustBe Vector(
        new VolumeBuilder()
          .withName("config-map-vol")
          .withConfigMap(new ConfigMapVolumeSourceBuilder().withName("configmap-some-app-id").build())
          .build(),
        new VolumeBuilder()
          .withName("secret-vol")
          .withSecret(new SecretVolumeSourceBuilder().withSecretName("flink-streamlet").build())
          .build(),
        Runner.DownwardApiVolume)

      cr.spec.volumeMounts.map(vm => (vm.getName, vm.getMountPath)) mustBe Vector(
        ("secret-vol", "/etc/cloudflow-runner-secret"),
        ("config-map-vol", "/etc/cloudflow-runner"),
        (Runner.DownwardApiVolumeMount.getName, Runner.DownwardApiVolumeMount.getMountPath))

      cr.getKind mustBe "FlinkApplication"
      cr.spec.serviceAccountName mustBe Name.ofServiceAccount
    }

    "create a valid FlinkApplication CR without resource requests" in {

      val flinkRunnerDefaults = FlinkRunnerDefaults(
        2,
        jobManagerDefaults = FlinkJobManagerDefaults(1, FlinkPodResourceDefaults()),
        taskManagerDefaults = FlinkTaskManagerDefaults(2, FlinkPodResourceDefaults()),
        prometheusRules = "sample rules")
      val cr = new FlinkRunner(flinkRunnerDefaults)
        .resource(deployment = deployment, app = app, configSecret = new SecretBuilder().build())

      cr.spec.serviceAccountName mustBe Name.ofServiceAccount
      cr.spec.jobManagerConfig.resources mustBe None
      cr.spec.taskManagerConfig.resources mustBe None
    }

    "convert the CRD to/from Json" in {
      Serialization.jsonMapper().registerModule(DefaultScalaModule)

      val cr = flinkRunner.resource(deployment = deployment, app = app, configSecret = new SecretBuilder().build())

      val jsonString = Serialization.jsonMapper().writeValueAsString(cr)
      val fromJson = Serialization.jsonMapper().readValue(jsonString, classOf[FlinkApp.Cr])

      fromJson.metadata.getName mustBe deployment.name
    }
  }
}
