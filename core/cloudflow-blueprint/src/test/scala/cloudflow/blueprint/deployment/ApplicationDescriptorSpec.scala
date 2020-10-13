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

package cloudflow.blueprint.deployment

import com.typesafe.config._
import org.scalatest._

import cloudflow.blueprint.{ Topic => BTopic, _ }

class ApplicationDescriptorSpec extends WordSpec with MustMatchers with EitherValues with OptionValues with GivenWhenThen {
  case class Foo(name: String)
  case class Bar(name: String)

  import BlueprintBuilder._

  // On Windows/Sandbox agents are not used.
  val agentPaths = Map(ApplicationDescriptor.PrometheusAgentKey -> "/app/prometheus/prometheus.jar")

  "An ApplicationDescriptor" should {
    "have a valid application id and secret name" in {
      Given("a verified blueprint with an invalid secret name")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute

      val ingressName =
        "ingressnamethatisverylongmorethan243characters000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
      val validSecretName =
        "ingressnamethatisverylongmorethan243characters000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
      val ingressRef = ingress.ref(ingressName)

      val verifiedBlueprint = Blueprint()
        .define(Vector(ingress))
        .use(ingressRef)
        .connect(BTopic("in"), ingressRef.out)
        .verified
        .right
        .get

      When("I create a deployment descriptor from that blueprint with an invalid application id")
      val appId          = "-monstrous-some-very-long-NAME-with-ü-in-the-middle-that-still-needs-more-characters-mite-12345."
      val truncatedAppId = "monstrous-some-very-long-name-with-u-in-the-middle-that-still"
      val appVersion     = "42-abcdef0"
      val image          = "image-1"
      val descriptor     = ApplicationDescriptor(appId, appVersion, image, verifiedBlueprint, agentPaths, BuildInfo.version)

      Then("the resulting descriptor application id and secret name must be valid")
      descriptor.appId mustBe truncatedAppId
      descriptor.deployments.head.secretName mustBe validSecretName
    }
    "be built correctly from a verified blueprint" in {
      Given("a verified blueprint")
      val ingress   = randomStreamlet().asIngress[Foo].withServerAttribute
      val processor = randomStreamlet().asProcessor[Foo, Bar].withRuntime("spark")
      val egress    = randomStreamlet().asEgress[Bar].withServerAttribute

      val ingressRef   = ingress.ref("ingress")
      val processorRef = processor.ref("processor")
      val egressRef    = egress.ref("egress")

      val verifiedBlueprint = Blueprint()
        .define(Vector(ingress, processor, egress))
        .use(ingressRef)
        .use(processorRef)
        .use(egressRef)
        .connect(BTopic("foos"), ingressRef.out, processorRef.in)
        .connect(BTopic("bars"), processorRef.out, egressRef.in)
        .verified
        .right
        .get

      When("I create a deployment descriptor from that blueprint")
      val appId      = "monstrous-mite-12345"
      val appVersion = "42-abcdef0"
      val image      = "image-1"
      val descriptor = ApplicationDescriptor(appId, appVersion, image, verifiedBlueprint, agentPaths, BuildInfo.version)

      Then("the descriptor must be valid")
      descriptor.appId mustBe appId
      descriptor.appVersion mustBe appVersion
      descriptor.deployments.size mustBe 3
      descriptor.deployments.map(_.image).toSet.size mustBe 1
      descriptor.deployments.map(_.image).toSet.head mustBe image
      descriptor.streamlets.size mustBe 3
      descriptor.deployments.flatMap(_.portMappings.values.map(_.name)).distinct.size mustBe 2

      And("the embedded streamlet deployments must be valid")
      val ingressDeployment   = descriptor.deployments.find(_.streamletName == ingressRef.name).value
      val processorDeployment = descriptor.deployments.find(_.streamletName == processorRef.name).value
      val egressDeployment    = descriptor.deployments.find(_.streamletName == egressRef.name).value

      val ingressContainerPort = StreamletDeployment.EndpointContainerPort
      val egressContainerPort  = StreamletDeployment.EndpointContainerPort

      ingressDeployment.name mustBe s"${appId}.${ingressRef.name}"
      ingressDeployment.runtime mustBe ingress.runtime.name
      ingressDeployment.image mustBe image
      ingressDeployment.className mustBe ingress.className
      ingressDeployment.endpoint mustBe Some(Endpoint(appId, ingressRef.name, ingressContainerPort))
      ingressDeployment.config.getInt("cloudflow.internal.server.container-port") mustBe ingressContainerPort
      ingressDeployment.portMappings.size mustBe 1
      ingressDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("out" -> "foos")
      ingressDeployment.replicas mustBe None

      processorDeployment.name mustBe s"${appId}.${processorRef.name}"
      processorDeployment.runtime mustBe processor.runtime.name
      processorDeployment.image mustBe image
      processorDeployment.className mustBe processor.className
      processorDeployment.endpoint mustBe None
      processorDeployment.config mustBe ConfigFactory.empty()
      processorDeployment.portMappings.size mustBe 2
      processorDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("in"  -> "foos")
      processorDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("out" -> "bars")
      processorDeployment.replicas mustBe None

      egressDeployment.name mustBe s"${appId}.${egressRef.name}"
      egressDeployment.runtime mustBe egress.runtime.name
      egressDeployment.image mustBe image
      egressDeployment.className mustBe egress.className
      egressDeployment.endpoint mustBe Some(Endpoint(appId, egressRef.name, egressContainerPort))
      egressDeployment.config.getInt("cloudflow.internal.server.container-port") mustBe egressContainerPort
      egressDeployment.portMappings.size mustBe 1
      egressDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("in" -> "bars")
      egressDeployment.replicas mustBe None
    }

    "be built correctly from a verified blueprint (with branch and open outlet)" in {
      Given("a verified blueprint")
      val ingress    = randomStreamlet().asIngress[Foo].withServerAttribute
      val processor  = randomStreamlet().asProcessor[Foo, Bar].withRuntime("spark")
      val egress     = randomStreamlet().asEgress[Bar]
      val processor2 = randomStreamlet().asProcessor[Bar, Foo]

      val ingressRef    = ingress.ref("ingress")
      val processorRef  = processor.ref("processor")
      val egressRef     = egress.ref("egress")
      val processor2Ref = processor2.ref("processor2")

      val verifiedBlueprint = Blueprint()
        .define(Vector(ingress, processor, egress, processor2))
        .use(ingressRef)
        .use(processorRef)
        .use(egressRef)
        .use(processor2Ref)
        .connect(BTopic("foos"), ingressRef.out, processorRef.in)
        .connect(BTopic("bars1"), processorRef.out, processor2Ref.in, egressRef.in)
        .connect(BTopic("foos2"), processor2Ref.out)
        .verified
        .right
        .value

      When("I create a deployment descriptor from that blueprint")
      val appId      = "noisy-nissan-42"
      val appVersion = "1-2345678"
      val image      = "image-1"
      val descriptor = ApplicationDescriptor(appId, appVersion, image, verifiedBlueprint, agentPaths, BuildInfo.version)

      Then("the descriptor must be valid")
      descriptor.deployments.size mustBe 4

      And("the embedded streamlet deployments must have the correct port mappings")
      val ingressDeployment    = descriptor.deployments.find(_.streamletName == ingressRef.name).value
      val processorDeployment  = descriptor.deployments.find(_.streamletName == processorRef.name).value
      val egressDeployment     = descriptor.deployments.find(_.streamletName == egressRef.name).value
      val processor2Deployment = descriptor.deployments.find(_.streamletName == processor2Ref.name).value

      ingressDeployment.portMappings.size mustBe 1
      ingressDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("out" -> "foos")

      processorDeployment.portMappings.size mustBe 2
      processorDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("in"  -> "foos")
      processorDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("out" -> "bars1")

      egressDeployment.portMappings.size mustBe 1
      egressDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("in" -> "bars1")

      processor2Deployment.portMappings.size mustBe 2
      processor2Deployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("in"  -> "bars1")
      processor2Deployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("out" -> "foos2")
    }

    "be built correctly from a verified blueprint (with dual-inlet merging)" in {
      Given("a verified blueprint")
      val ingress1 = randomStreamlet().asIngress[Foo].withServerAttribute
      val ingress2 = randomStreamlet().asIngress[Foo].withServerAttribute
      val merge    = randomStreamlet().asMerge[Foo, Foo, Bar].withRuntime("spark")

      val ingress1Ref = ingress1.ref("ingress1")
      val ingress2Ref = ingress2.ref("ingress2")
      val mergeRef    = merge.ref("merge")

      val blueprint = Blueprint()
        .define(Vector(ingress1, ingress2, merge))
        .use(ingress1Ref)
        .use(ingress2Ref)
        .use(mergeRef)
        .connect(BTopic("foos1"), ingress1Ref.out, mergeRef.in0)
        .connect(BTopic("foos2"), ingress2Ref.out, mergeRef.in1)
        .connect(BTopic("merged-bars"), mergeRef.out)
        .verify
      val verifiedBlueprint = blueprint.verified.right.value

      When("I create a deployment descriptor from that blueprint")
      val appId      = "funky-foofighter-9862"
      val appVersion = "12-3456789"
      val image      = "image-1"
      val descriptor = ApplicationDescriptor(appId, appVersion, image, verifiedBlueprint, agentPaths, BuildInfo.version)

      Then("the descriptor must be valid")
      descriptor.deployments.size mustBe 3

      And("the embedded streamlet deployments must have the correct port mappings")
      val ingress1Deployment = descriptor.deployments.find(_.streamletName == ingress1Ref.name).value
      val ingress2Deployment = descriptor.deployments.find(_.streamletName == ingress2Ref.name).value
      val mergeDeployment    = descriptor.deployments.find(_.streamletName == mergeRef.name).value

      ingress1Deployment.portMappings.size mustBe 1
      ingress1Deployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("out" -> "foos1")

      ingress2Deployment.portMappings.size mustBe 1
      ingress2Deployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("out" -> "foos2")

      mergeDeployment.portMappings.size mustBe 3
      mergeDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("in-0" -> "foos1")
      mergeDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("in-1" -> "foos2")
      mergeDeployment.portMappings.map { case (port, sp) => port -> sp.id } must contain("out"  -> "merged-bars")
    }
  }

}
