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
import cloudflow.operator.action.Util.PrometheusAgentKey
import cloudflow.operator.action._
import io.fabric8.kubernetes.client.utils.Serialization
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{ GivenWhenThen, OptionValues }

import scala.jdk.CollectionConverters._

class AkkaRunnerSpec
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
  val namespace = "test-ns"
  val prometheusJarPath = "/app/prometheus/prometheus.jar"
  val prometheusConfig = PrometheusConfig("(prometheus rules)")
  val akkaRunner = new AkkaRunner(ctx.akkaRunnerDefaults)

  "AkkaRunner" should {

    val appId = "some-app-id"
    val appVersion = "42-abcdef0"
    val agentPaths = Map(PrometheusAgentKey -> "/app/prometheus/prometheus.jar")
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
      runtime = "akka",
      image = image,
      streamletName = "akka-streamlet",
      className = "cloudflow.operator.runner.AkkaRunner",
      endpoint = None,
      secretName = "akka-streamlet",
      config = Serialization.jsonMapper().readTree("{}"),
      portMappings = Map.empty,
      volumeMounts = Seq.empty,
      replicas = None)

    "read from config custom labels and add them to the pod spec" in {

      val crd =
        akkaRunner.resource(
          deployment = deployment,
          app = app,
          configSecret = getSecret("""
                                      |kubernetes.pods.pod {
                                      | labels {
                                      |    "key1" : "value1",
                                      |    "key2" : "value2"
                                      | }
                                      |}
                                    """.stripMargin))

      crd.getSpec.getTemplate.getMetadata.getLabels.get("key1") mustBe "value1"
      crd.getSpec.getTemplate.getMetadata.getLabels.get("key2") mustBe "value2"
    }

    "read from config custom annotaions and add them to the pod spec" in {

      val crd =
        akkaRunner.resource(
          deployment = deployment,
          app = app,
          configSecret = getSecret("""
                                    |kubernetes.pods.pod {
                                    | annotations {
                                    |    "key1" : "value1",
                                    |    "key2" : "value2"
                                    | }
                                    |}
                                    """.stripMargin))

      crd.getSpec.getTemplate.getMetadata.getAnnotations.get("key1") mustBe "value1"
      crd.getSpec.getTemplate.getMetadata.getAnnotations.get("key2") mustBe "value2"
    }

    "read from config custom ports and add them to the pod spec" in {

      val crd1 = akkaRunner.resource(
        deployment = deployment,
        app = app,
        configSecret = getSecret("""
              |kubernetes.pods.pod {
              |   volumes {
              |     foo.secret.name = fooo
              |     bar.secret.name = barr
              |   }
              |   containers.container {
              |     ports = [{container-port = 1234},{container-port = 5678}]
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

      crd1.getSpec.getTemplate.getSpec.getContainers.asScala.map { each => }
      crd1.getSpec.getTemplate.getSpec.getContainers.asScala.head.getPorts.asScala
        .map(_.getContainerPort) must contain allElementsOf
      List(1234, 5678)
    }

    "read from config custom secrets and mount them" in {

      val crd = akkaRunner.resource(
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

      crd.getSpec.getTemplate.getSpec.getVolumes.asScala
        .filter(_.getSecret != null)
        .map(v => (v.getName, v.getSecret.getSecretName)) must contain allElementsOf List(
        ("foo", "mysecret"),
        ("bar", "yoursecret"))

      crd.getSpec.getTemplate.getSpec.getContainers.asScala.head.getVolumeMounts.asScala.map(vm =>
        (vm.getName, vm.getMountPath, vm.getReadOnly)) must contain allElementsOf List(
        ("foo", "/etc/my/file", true),
        ("bar", "/etc/mc/fly", false))

    }
  }
}
