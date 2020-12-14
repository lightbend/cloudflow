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

import cloudflow.blueprint._
import cloudflow.blueprint.deployment.{ PrometheusConfig, StreamletDeployment }
import com.typesafe.config.ConfigFactory
import org.scalatest._
import skuber.{ Volume, _ }

import cloudflow.operator.action._

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
  val akkaRunner        = new AkkaRunner(ctx.akkaRunnerDefaults)

  "AkkaRunner" should {

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
      runtime = "akka",
      image = image,
      streamletName = "akka-streamlet",
      className = "cloudflow.operator.runner.AkkaRunner",
      endpoint = None,
      secretName = "akka-streamlet",
      config = ConfigFactory.empty(),
      portMappings = Map.empty,
      volumeMounts = None,
      replicas = None
    )

    "read from config custom labels and add them to the pod spec" in {

      val crd = akkaRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
                |kubernetes.pods.pod {
                | labels {
                |    "key1" : "value1",
                |    "key2" : "value2"
                | }
                |}
                """.stripMargin.getBytes()
          )
        )
      )

      crd.spec.get.template.metadata.labels.get("key1") mustBe Some("value1")
      crd.spec.get.template.metadata.labels.get("key2") mustBe Some("value2")
    }

    "read from config custom ports and add them to the pod spec" in {

      val crd = akkaRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = Secret(
          metadata = ObjectMeta(),
          data = Map(
            cloudflow.operator.event.ConfigInputChangeEvent.PodsConfigDataKey ->
                """
              |kubernetes.pods.pod {
              |   containers.container {
              |     ports = [1234,5678]
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
        )
      )
      crd.spec.get.template.spec.get.containers.map { each =>
      }
      crd.spec.get.template.spec.get.containers.head.ports must contain allElementsOf
        List(Container.Port(1234), Container.Port(5678))
    }

    "read from config custom secrets and mount them" in {

      val crd = akkaRunner.resource(
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
        )
      )

      crd.spec.get.template.spec.get.volumes must contain allElementsOf List(
        Volume("foo", Volume.Secret(secretName = "mysecret")),
        Volume("bar", Volume.Secret(secretName = "yoursecret"))
      )

      crd.spec.get.template.spec.get.containers.head.volumeMounts must contain allElementsOf List(
        Volume.Mount("foo", "/etc/my/file", true),
        Volume.Mount("bar", "/etc/mc/fly", false)
      )

    }
  }
}
