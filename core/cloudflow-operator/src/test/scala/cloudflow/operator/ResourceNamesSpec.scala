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

import action._
import skuber._
import org.scalatest.{ ConfigMap ⇒ _, _ }

import cloudflow.blueprint._
import BlueprintBuilder._
import cloudflow.operator.runner._

class ResourceNamesSpec extends WordSpec with MustMatchers with GivenWhenThen with EitherValues with Inspectors with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)

  val namespace  = "resourcetest"
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

  val DNS1035regex = "[a-z]([-a-z0-9]*[a-z0-9])"
  val DNS1123Regex = """[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*"""

  // appId + ingress name more than 63 characters. Each 40 characters.
  val testApp01 = {
    val appVersion = "001"
    val appId      = "longappid9012345678900123456789001234567890"
    val image      = "image-1"

    val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
    val egress  = randomStreamlet().asEgress[Foo].withServerAttribute

    val ingressRef = ingress.ref("longingressname6789012345678901234567890012345678900123456789001234567890")
    val egressRef  = egress.ref("longegressname56789012345678901234567890012345678900123456789001234567890")

    val verifiedBlueprint = Blueprint()
      .define(Vector(ingress, egress))
      .use(ingressRef)
      .use(egressRef)
      .connect(Topic("foos"), ingressRef.out, egressRef.in)
      .verified
      .right
      .value

    CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths))
  }

  // appId 80 characters.
  val testApp02 = {
    val appVersion = "001"
    val appId      = "longappid9012345678900123456789001234567890012345678900123456789012345678901234567890"
    val image      = "image-1"

    val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
    val egress  = streamlet("sparkyStreamlet", "spark").asEgress[Foo].withServerAttribute

    val ingressRef = ingress.ref("shortingress")
    val egressRef  = egress.ref("shortegress")

    val verifiedBlueprint2 = Blueprint()
      .define(Vector(ingress, egress))
      .use(ingressRef)
      .use(egressRef)
      .connect(Topic("foos"), ingressRef.out, egressRef.in)
      .verified
      .right
      .value

    CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint2, agentPaths))
  }
  val secret = Secret(metadata = ObjectMeta())
  "Deployments" should {
    "have long names truncate to 63 characters when coming from AkkaRunner" in {
      val deployment = AkkaRunner.resource(testApp01.spec.deployments.head, testApp01, secret, namespace)

      deployment.metadata.name.length mustEqual 63

    }
  }

  "Pod templates" should {
    "have long names truncate to 63 characters when coming from AkkaRunner" in {

      val deployment = AkkaRunner.resource(testApp01.spec.deployments.head, testApp01, secret, namespace)

      deployment.copySpec.template.metadata.name.length mustEqual 63

    }
  }

  "Containers" should {
    "have long names truncate to 63 characters when coming from AkkaRunner" in {
      val deployment = AkkaRunner.resource(testApp01.spec.deployments.head, testApp01, secret, namespace)

      deployment.getPodSpec.get.containers.head.name.length mustEqual 63

    }
  }

  "Volumes" should {
    "have long names truncate to 63 characters when coming from AkkaRunner" in {
      val deployment = AkkaRunner.resource(testApp01.spec.deployments.head, testApp01, secret, namespace)

      deployment.getPodSpec.get.volumes.foreach { vol ⇒
        assert(vol.name.length <= 63)
      }

    }
  }

  "Volume mounts" should {
    "have long names truncate to 63 characters when coming from AkkaRunner" in {
      val deployment = AkkaRunner.resource(testApp01.spec.deployments.head, testApp01, secret, namespace)

      deployment.getPodSpec.get.containers.head.volumeMounts.foreach { mount ⇒
        assert(mount.name.length <= 63)
      }

    }
  }

  "ConfigMaps" should {
    "have long names truncate to 63 characters when coming from AkkaRunner" in {
      val configMap = AkkaRunner.configResource(testApp01.spec.deployments.head, testApp01, namespace)

      configMap.metadata.name.length mustEqual 63

    }

    "have long names truncate to 63 characters when coming from SparkRunner" in {
      val configMap = SparkRunner.configResource(testApp01.spec.deployments.head, testApp01, namespace)

      configMap.metadata.name.length mustEqual 63

    }
  }

  "Custom resources" should {
    "have long names truncate to 63 characters when coming from SparkRunner" in {
      val deployment = SparkRunner.resource(testApp01.spec.deployments.head, testApp01, Secret(metadata = ObjectMeta()), namespace)

      deployment.metadata.name.length mustEqual 63

    }
  }

  "Services" should {
    "have long names truncate to 63 characters when coming from EndpointActions" in {
      val endpointActions = EndpointActions(testApp01, None, namespace)

      endpointActions
        .collect {
          case action: CreateOrUpdateAction[_] => action.resource.asInstanceOf[Service]
        }
        .head
        .metadata
        .name
        .length mustEqual 63

    }
  }

  "PersistentVolumeClaim" should {
    "have long names truncate to 63 characters when coming from PrepareNamespaceActions" in {
      val appActions = PrepareNamespaceActions(testApp02, namespace, CloudflowLabels(testApp02), testApp02.metadata.ownerReferences)
      appActions
        .collect {
          case a: ResourceAction[_] if a.resource.isInstanceOf[PersistentVolumeClaim] =>
            a.resource.asInstanceOf[PersistentVolumeClaim]
        }
        .head
        .metadata
        .name
        .length mustEqual 63
    }
  }

  "A name" should {
    "keep only relevant letters when DNS-1035 normalized" in {
      val orig     = "This-αêÍ_and that.and"
      val expected = "this-ei-andthat-and"
      Name.makeDNS1039Compatible(orig) mustEqual expected
    }

    "not end or start with '-' after removing illegal characters when DNS-1035 normalized" in {
      val orig     = "α-test-a-α"
      val expected = "test-a"
      Name.makeDNS1039Compatible(orig) mustEqual expected
    }

    "not end or start with '-' when DNS-1035 normalized, with truncate" in {
      val orig     = "-2345678901234567890123456789012345678901234567890123456789012.456"
      val expected = "2345678901234567890123456789012345678901234567890123456789012"
      Name.makeDNS1039Compatible(orig) mustEqual expected
    }

    "keep only relevant letters when DNS-1123 normalized" in {
      val orig     = "This-αêÍ_and that.and-some_àôô.more"
      val expected = "this-ei-andthat.and-some-aoo.more"
      Name.makeDNS1123Compatible(orig) mustEqual expected
    }

    "not end or start with '-' or '.' after removing illegal characters when DNS-1123 normalized" in {
      val orig     = "α.test.a-α"
      val expected = "test.a"
      Name.makeDNS1123Compatible(orig) mustEqual expected
    }

    "not end or start with '-' or '.' when DNS-1123 normalized, with truncate" in {
      val orig     = "-2345678901234567890123456789012345678901234567890123456789012.456"
      val expected = "2345678901234567890123456789012345678901234567890123456789012"
      Name.makeDNS1123Compatible(orig) mustEqual expected
    }
  }

  "Blueprint verification" should {
    "fail with non compliant names" in {
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress  = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("bad-Ingress")
      val egressRef  = egress.ref("bad-Egress")

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
