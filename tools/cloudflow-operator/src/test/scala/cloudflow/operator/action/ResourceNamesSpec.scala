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

package cloudflow.operator.action

import cloudflow.blueprint._
import _root_.cloudflow.blueprint.BlueprintBuilder._
import akka.datap.crd.App
import akka.kube.actions.{ Action, CreateOrReplaceAction, OperatorAction }
import cloudflow.operator.action.EndpointActions.CreateServiceAction
import cloudflow.operator.action.runner._
import io.fabric8.kubernetes.api.model.{ SecretBuilder, Service }
import org.scalatest.{ EitherValues, GivenWhenThen, Inspectors }
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._

class ResourceNamesSpec
    extends AnyWordSpec
    with Matchers
    with GivenWhenThen
    with EitherValues
    with Inspectors
    with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)

  val namespace = "resourcetest"
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

  val DNS1035regex = "[a-z]([-a-z0-9]*[a-z0-9])"
  val DNS1123Regex = """[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*"""

  // appId + ingress name more than 63 characters. Each 40 characters.
  val testApp01 = {
    val appVersion = "001"
    val appId =
      "longappid9012345678900123456789001234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789"
    val image = "image-1"

    val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
    val egress = randomStreamlet().asEgress[Foo].withServerAttribute

    val ingressRef = ingress.ref("longingressname6789012345678901234567890012345678900123456789001234567890")
    val egressRef = egress.ref("longegressname56789012345678901234567890012345678900123456789001234567890")

    val verifiedBlueprint = Blueprint()
      .define(Vector(ingress, egress))
      .use(ingressRef)
      .use(egressRef)
      .connect(Topic("foos"), ingressRef.out, egressRef.in)
      .verified
      .right
      .value

    App.Cr(
      spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
      metadata = CloudflowApplicationSpecBuilder.demoMetadata)
  }
  val akkaRunner = new AkkaRunner(ctx.akkaRunnerDefaults)
  val sparkRunner = new SparkRunner(ctx.sparkRunnerDefaults)

  // appId 80 characters.
  val testApp02 = {
    val appVersion = "001"
    val appId = "longappid9012345678900123456789001234567890012345678900123456789012345678901234567890"
    val image = "image-1"

    val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
    val egress = streamlet("sparkyStreamlet", "spark").asEgress[Foo].withServerAttribute

    val ingressRef = ingress.ref("shortingress")
    val egressRef = egress.ref("shortegress")

    val verifiedBlueprint2 = Blueprint()
      .define(Vector(ingress, egress))
      .use(ingressRef)
      .use(egressRef)
      .connect(Topic("foos"), ingressRef.out, egressRef.in)
      .verified
      .right
      .value

    App.Cr(
      spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint2, agentPaths),
      metadata = CloudflowApplicationSpecBuilder.demoMetadata)
  }

  val secret = new SecretBuilder().build()

  "Deployments" should {
    "have long names truncate to 63 characters when coming from AkkaRunner" in {
      val deployment = akkaRunner.resource(testApp01.spec.deployments.head, testApp01, secret)

      deployment.getMetadata.getName.length mustEqual 63
    }
  }

  "Pod templates" should {
    "have long names truncate to 63 characters when coming from AkkaRunner" in {

      val deployment = akkaRunner.resource(testApp01.spec.deployments.head, testApp01, secret)

      deployment.getSpec.getTemplate.getMetadata.getName.length mustEqual 63

    }
  }

  "Containers" should {
    "have long names truncate to 63 characters when coming from AkkaRunner" in {
      val deployment = akkaRunner.resource(testApp01.spec.deployments.head, testApp01, secret)

      deployment.getSpec.getTemplate.getSpec.getContainers.asScala.head.getName.length mustEqual 63

    }
  }

  "Volumes" should {
    "have long names truncate to 253 characters when coming from AkkaRunner" in {
      val deployment = akkaRunner.resource(testApp01.spec.deployments.head, testApp01, secret)

      deployment.getSpec.getTemplate.getSpec.getVolumes.asScala.foreach { vol =>
        assert(vol.getName.length <= 253)
      }

    }
  }

  "Volume mounts" should {
    "have long names truncate to 253 characters when coming from AkkaRunner" in {
      val deployment = akkaRunner.resource(testApp01.spec.deployments.head, testApp01, secret)

      deployment.getSpec.getTemplate.getSpec.getContainers.asScala.head.getVolumeMounts.asScala.foreach { mount =>
        assert(mount.getName.length <= 253)
      }

    }
  }

  "ConfigMaps" should {
    "have long names truncate to 253 characters when coming from AkkaRunner" in {
      val configMap = akkaRunner.configResource(testApp01.spec.deployments.head, testApp01)

      configMap.getMetadata.getName.length must be <= 253

    }

    "have long names truncate to 253 characters when coming from SparkRunner" in {
      val configMap = sparkRunner.configResource(testApp01.spec.deployments.head, testApp01)

      configMap.getMetadata.getName.length must be <= 253

    }
  }

  "Custom resources" should {
    "have long names truncate to 253 characters when coming from SparkRunner" in {
      val deployment = sparkRunner.resource(testApp01.spec.deployments.head, testApp01, secret)
      // name of pod is dns 1039 and max 63
      deployment.getMetadata.getName.length mustEqual 63

    }
  }

  "Services" should {
    "have long names truncate to 63 characters when coming from EndpointActions" in {
      val endpointActions = EndpointActions(testApp01, None)

      println(endpointActions.mkString)
      endpointActions
        .collect {
          case action: CreateServiceAction =>
            action.service
        }
        .head
        .getMetadata
        .getName
        .length mustEqual 63

    }
  }

  "A name" should {
    "keep only relevant letters when DNS-1035 normalized" in {
      val orig = "This-αêÍ_and that.and"
      val expected = "this-ei-andthat-and"
      Name.makeDNS1039Compatible(orig) mustEqual expected
    }

    "not end or start with '-' after removing illegal characters when DNS-1035 normalized" in {
      val orig = "α-test-a-α"
      val expected = "test-a"
      Name.makeDNS1039Compatible(orig) mustEqual expected
    }

    "not end or start with '-' when DNS-1035 normalized, with truncate" in {
      val orig = "-2345678901234567890123456789012345678901234567890123456789012.456"
      val expected = "2345678901234567890123456789012345678901234567890123456789012"
      Name.makeDNS1039Compatible(orig) mustEqual expected
    }

    "keep only relevant letters when DNS-1123 normalized" in {
      val orig = "This-αêÍ_and that.and-some_àôô.more"
      val expected = "this-ei-andthat.and-some-aoo.more"
      Name.makeDNS1123CompatibleSubDomainName(orig) mustEqual expected
    }

    "not end or start with '-' or '.' after removing illegal characters when DNS-1123 normalized" in {
      val orig = "α.test.a-α"
      val expected = "test.a"
      Name.makeDNS1123CompatibleSubDomainName(orig) mustEqual expected
    }

    "not end or start with '-' or '.' when DNS-1123 normalized, with truncate" in {
      val orig =
        "-234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123.456"
      val expected =
        "234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123"
      Name.makeDNS1123CompatibleSubDomainName(orig) mustEqual expected
    }
  }

  "Blueprint verification" should {
    "fail with non compliant names" in {
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("bad-Ingress")
      val egressRef = egress.ref("bad-Egress")

      Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(Topic("foos"), ingressRef.out, egressRef.in)
        .verified
        .left
        .value mustEqual Vector(InvalidStreamletName("bad-Ingress"), InvalidStreamletName("bad-Egress"))

    }
  }

}
