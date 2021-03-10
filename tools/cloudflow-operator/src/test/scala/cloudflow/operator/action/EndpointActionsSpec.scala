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

import akka.datap.crd.App
import akka.kube.actions.{ CreateOrReplaceAction, DeleteAction }
import cloudflow.blueprint._
import cloudflow.blueprint.BlueprintBuilder._
import cloudflow.operator.action.EndpointActions.CreateServiceAction
import io.fabric8.kubernetes.api.model.{ IntOrString, Service }
import org.scalatest.{ EitherValues, GivenWhenThen, Inspectors }
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._

class EndpointActionsSpec
    extends AnyWordSpec
    with Matchers
    with GivenWhenThen
    with EitherValues
    with Inspectors
    with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

  "EndpointActions" should {
    "create ingresses and services when there is no previous application deployment" in {

      Given("no current app and a new app with an ingress")
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

      val appId = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val image = "image-1"

      val currentApp = None
      val newApp = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("endpoint actions are created from a new app")
      val actions = EndpointActions(newApp, currentApp)

      Then("only create endpoint actions must be created for all server attributed streamlets")
      val createActions = actions.collect { case c: CreateServiceAction => c }

      val services = createActions.map(_.service)
      val endpoints = newApp.spec.deployments.flatMap(_.endpoint).distinct

      createActions.size mustBe actions.size
      services.size mustBe endpoints.size
      services.foreach { service =>
        assertService(service, newApp.spec)
      }
    }

    "do nothing when the new applications requires the same endpoints as the current one" in {

      Given("a current app")
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

      val appId = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val image = "image-1"

      val newApp = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)
      val currentApp = Some(newApp)

      When("nothing changes in the new app")
      val actions = EndpointActions(newApp, currentApp)

      Then("no actions should be created")
      actions mustBe empty
    }

    "delete services when new app removes endpoints" in {
      Given("a current app, ingress -> egress")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef = egress.ref("egress")
      val bp = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(Topic("foos"), ingressRef.out, egressRef.in)

      val verifiedBlueprint = bp.verified.right.value

      val appId = "killer-mike-12345"
      val appVersion = "42-abcdef0"
      val newAppVersion = "43-abcdef0"
      val image = "image-1"
      val currentApp = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("the new app removes the egress")
      val newBp =
        bp.disconnect(egressRef.in).remove(egressRef.name)
      val newApp = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, newBp.verified.right.value, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)
      val actions = EndpointActions(newApp, Some(currentApp))

      Then("delete actions should be created")
      actions.size mustBe 1
      val deleteActions = actions.collect { case d: DeleteAction[_] => d }

      val serviceNames = deleteActions.map(_.resourceName)

      serviceNames.size mustBe 1
    }

    "create a new service when an endpoint is added" in {

      Given("a current app with just an ingress")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val bp = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .connect(Topic("foos"), ingressRef.out)

      val verifiedBlueprint = bp.verified.right.value

      val appId = "odd-future-12345"
      val appVersion = "42-abcdef0"
      val image = "image-1"
      val currentApp = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("the new app adds an endpoint, ingress -> egress")
      val egressRef = egress.ref("egress")
      val newBp = bp
        .use(egressRef)
        .connect(Topic("foos"), egressRef.in)
      val newAppVersion = "43-abcdef0"
      val newApp = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, newBp.verified.right.value, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      Then("create actions for service should be created for the new endpoint")
      val actions = EndpointActions(newApp, Some(currentApp))
      actions.size mustBe 1
      val createActions = actions.collect { case a: CreateServiceAction => a }

      val services = createActions.map(_.service)

      services.size mustBe 1
      services.foreach { service =>
        assertService(service, newApp.spec)
      }
    }
  }

  def assertService(service: Service, app: App.Spec) = {
    val deployment =
      app.deployments.find(deployment => Name.ofService(deployment.name) == service.getMetadata.getName).value
    val endpoint = deployment.endpoint.value
    service.getMetadata.getName mustEqual Name.ofService(deployment.name)
    val serviceSelector: Map[String, String] = service.getSpec.getSelector.asScala.toMap
    serviceSelector(CloudflowLabels.Name) mustEqual Name.ofPod(deployment.name)
    service.getSpec.getType mustEqual "ClusterIP"
    val ports = service.getSpec.getPorts.asScala
    ports must have size 1

    forAll(ports) { port =>
      val containerPort = endpoint.containerPort.getOrElse(-1)
      port.getPort mustEqual containerPort
      port.getName mustEqual Name.ofContainerPort(containerPort)
      port.getTargetPort.getStrVal mustEqual Name.ofContainerPort(containerPort)
    }
  }

}
