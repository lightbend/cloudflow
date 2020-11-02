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
import skuber.Resource.Quantity
import skuber._

import cloudflow.blueprint._
import cloudflow.blueprint.deployment.{ PrometheusConfig, StreamletDeployment }
import cloudflow.operator._
import cloudflow.operator.action.runner.FlinkResource._

class FlinkRunnerSpec extends WordSpecLike with OptionValues with MustMatchers with GivenWhenThen with TestDeploymentContext {

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

  "FlinkRunner" should {

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

    "read from config environment variables and resource requirements and add them to the jobmanager and taskmanager pods specs" in {

      val crd = FlinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
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
              |""".stripMargin.getBytes()
          )
        ),
        namespace = namespace
      )

      crd.metadata.namespace mustBe namespace
      crd.metadata.name mustBe appId
      val imageWithoutRegistry = image.split("/").tail.mkString("/")
      crd.spec.image must include(imageWithoutRegistry)
      crd.spec.entryClass mustBe "cloudflow.runner.Runner"

      crd.spec.volumes mustBe Vector(
        Volume("config-map-vol", Volume.ConfigMapVolumeSource("configmap-some-app-id")),
        Volume("secret-vol", Volume.Secret("flink-streamlet")),
        Runner.DownwardApiVolume
      )
      crd.spec.jobManagerConfig.envConfig.get.env.get mustBe Vector(EnvVar("FOO", EnvVar.StringValue("BAR")))
      crd.spec.taskManagerConfig.envConfig.get.env.get mustBe Vector(EnvVar("FOO", EnvVar.StringValue("BAR")))
      crd.spec.jobManagerConfig.resources.get.requests mustBe Map(
        Resource.cpu    -> ctx.flinkRunnerDefaults.jobManagerDefaults.resources.cpuRequest.get,
        Resource.memory -> Quantity("512M")
      )
      crd.spec.taskManagerConfig.resources.get.requests mustBe Map(
        Resource.cpu    -> ctx.flinkRunnerDefaults.taskManagerDefaults.resources.cpuRequest.get,
        Resource.memory -> Quantity("512M")
      )
      crd.spec.jobManagerConfig.resources.get.limits mustBe Map(
        Resource.cpu    -> ctx.flinkRunnerDefaults.jobManagerDefaults.resources.cpuLimit.get,
        Resource.memory -> Quantity("1024M")
      )
      crd.spec.taskManagerConfig.resources.get.limits mustBe Map(
        Resource.cpu    -> ctx.flinkRunnerDefaults.taskManagerDefaults.resources.cpuLimit.get,
        Resource.memory -> Quantity("1024M")
      )
    }

    "read from config custom labels and add them to the jobmanager and taskmanager pods specs" in {

      val crd = FlinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
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
                """.stripMargin.getBytes()
          )
        ),
        namespace = namespace
      )

      crd.spec.jobManagerConfig.envConfig.get.env.get mustBe Vector(EnvVar("FOO", EnvVar.StringValue("BAR")))
      crd.spec.taskManagerConfig.envConfig.get.env.get mustBe Vector(EnvVar("FOO", EnvVar.StringValue("BAR")))

      crd.metadata.labels.get("key1") mustBe Some("value1")
      crd.metadata.labels.get("key2") mustBe Some("value2")
    }

    "read from config custom secrets and mount them in jobmanager and taskmanager pods" in {

      val crd = FlinkRunner.resource(
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
                """.stripMargin.getBytes()
          )
        ),
        namespace = namespace
      )

      crd.spec.volumes must contain allElementsOf List(
        Volume("foo", Volume.Secret(secretName = "mysecret")),
        Volume("bar", Volume.Secret(secretName = "yoursecret"))
      )

      crd.spec.volumeMounts must contain allElementsOf List(
        Volume.Mount("foo", "/etc/my/file", true),
        Volume.Mount("bar", "/etc/mc/fly", false)
      )

    }

    "read values from pod configuration key JAVA_OPTS and put it in Flink conf in env.java.opts" in {

      val crd = FlinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
              |kubernetes.pods.pod.containers.container {
              |  env = [
              |    { name = "JAVA_OPTS"
              |      value = "-XX:MaxRAMPercentage=40.0"
              |    }
              |  ]
              |}
              |""".stripMargin.getBytes()
          )
        ),
        namespace = namespace
      )

      crd.spec.flinkConfig.get("env.java.opts") mustBe Some("-XX:MaxRAMPercentage=40.0")
    }

    "configure env.java.opts from runtime Flink conf, overriding what is provided as JAVA_OPTS value in the pod configuration" in {

      val crd = FlinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
              |kubernetes.pods.pod.containers.container {
              |  env = [
              |    { name = "JAVA_OPTS"
              |      value = "-XX:MaxRAMPercentage=40.0"
              |    }
              |   ]
              |}
              |        """.stripMargin.getBytes(),
            cloudflow.operator.event.ConfigInputChangeEvent.RuntimeConfigDataKey ->
                """flink.env.java.opts = "-XX:-DisableExplicitGC"""".getBytes()
          )
        ),
        namespace = namespace
      )

      crd.spec.flinkConfig.get("env.java.opts") mustBe Some("-XX:-DisableExplicitGC")
    }

    "create a valid FlinkApplication CR" in {

      val crd = FlinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(metadata = ObjectMeta()),
        namespace = namespace
      )

      crd.metadata.namespace mustBe namespace
      crd.metadata.name mustBe appId
      val imageWithoutRegistry = image.split("/").tail.mkString("/")
      crd.spec.image must include(imageWithoutRegistry)
      crd.spec.entryClass mustBe "cloudflow.runner.Runner"

      crd.spec.volumes mustBe Vector(
        Volume("config-map-vol", Volume.ConfigMapVolumeSource("configmap-some-app-id")),
        Volume("secret-vol", Volume.Secret("flink-streamlet")),
        Runner.DownwardApiVolume
      )

      crd.spec.volumeMounts mustBe Vector(
        Volume.Mount("secret-vol", "/etc/cloudflow-runner-secret"),
        Volume.Mount("config-map-vol", "/etc/cloudflow-runner"),
        Runner.DownwardApiVolumeMount
      )

      crd.kind mustBe "FlinkApplication"
      crd.spec.serviceAccountName mustBe Name.ofServiceAccount
    }

    "create a valid FlinkApplication CR without resource requests" in {

      val dc = ctx.copy(
        flinkRunnerDefaults = FlinkRunnerDefaults(
          2,
          jobManagerDefaults = FlinkJobManagerDefaults(1, FlinkPodResourceDefaults()),
          taskManagerDefaults = FlinkTaskManagerDefaults(2, FlinkPodResourceDefaults()),
          prometheusRules = "sample rules"
        )
      )
      val crd = FlinkRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(metadata = ObjectMeta()),
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
        configSecret = Secret(metadata = ObjectMeta()),
        namespace = namespace
      )

      val jsonString = Json.toJson(cr).toString()
      val fromJson   = Json.parse(jsonString).validate[CR]
      fromJson match {
        case err: JsError ⇒ fail(err.toString)
        case _            ⇒
      }
    }
  }
}
