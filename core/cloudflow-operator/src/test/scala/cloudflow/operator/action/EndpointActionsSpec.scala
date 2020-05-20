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
package action

import org.scalatest._

import skuber._

import cloudflow.blueprint._

import BlueprintBuilder._

class EndpointActionsSpec
    extends WordSpec
    with MustMatchers
    with GivenWhenThen
    with EitherValues
    with Inspectors
    with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)
  val namespace  = "ns"
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

  "EndpointActions" should {
    "create ingresses and services when there is no previous application deployment" in {

      Given("no current app and a new app with an ingress")
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

      val appId      = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val image      = "image-1"

      val currentApp = None
      val newApp     = CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths))

      When("endpoint actions are created from a new app")
      val actions = EndpointActions(newApp, currentApp, namespace)

      Then("only create endpoint actions must be created for all server attributed streamlets")
      val createActions = actions.collect { case c: CreateOrUpdateAction[_] ⇒ c }

      val services = createActions.map(_.resource).collect {
        case service: Service ⇒ service
      }
      val endpoints = newApp.spec.deployments.flatMap(_.endpoint).distinct

      createActions.size mustBe actions.size
      services.size mustBe endpoints.size
      services.foreach { service ⇒
        assertService(service, newApp.spec)
      }
    }

    "do nothing when the new applications requires the same endpoints as the current one" in {

      Given("a current app")
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

      val appId      = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val image      = "image-1"

      val newApp     = CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths))
      val currentApp = Some(newApp)

      When("nothing changes in the new app")
      val actions = EndpointActions(newApp, currentApp, namespace)

      Then("no actions should be created")
      actions mustBe empty
    }

    "delete services when new app removes endpoints" in {
      Given("a current app, ingress -> egress")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress  = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef  = egress.ref("egress")
      val bp = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(Topic("foos"), ingressRef.out, egressRef.in)

      val verifiedBlueprint = bp.verified.right.value

      val appId         = "killer-mike-12345"
      val appVersion    = "42-abcdef0"
      val newAppVersion = "43-abcdef0"
      val image         = "image-1"
      val currentApp    = CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths))

      When("the new app removes the egress")
      val newBp =
        bp.disconnect(egressRef.in).remove(egressRef.name)
      val newApp =
        CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, newAppVersion, image, newBp.verified.right.value, agentPaths))
      val actions = EndpointActions(newApp, Some(currentApp), namespace)

      Then("delete actions should be created")
      actions.size mustBe 1
      val deleteActions = actions.collect { case d: DeleteAction[_] ⇒ d }

      val services = deleteActions.map(_.resource).collect {
        case service: Service ⇒ service
      }

      services.size mustBe 1
      services.foreach { service ⇒
        assertService(service, currentApp.spec)
      }

    }

    "create a new service when an endpoint is added" in {

      Given("a current app with just an ingress")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress  = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val bp = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .connect(Topic("foos"), ingressRef.out)

      val verifiedBlueprint = bp.verified.right.value

      val appId      = "odd-future-12345"
      val appVersion = "42-abcdef0"
      val image      = "image-1"
      val currentApp = CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths))

      When("the new app adds an endpoint, ingress -> egress")
      val egressRef = egress.ref("egress")
      val newBp = bp
        .use(egressRef)
        .connect(Topic("foos"), egressRef.in)
      val newAppVersion = "43-abcdef0"
      val newApp =
        CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, newAppVersion, image, newBp.verified.right.value, agentPaths))

      Then("create actions for service should be created for the new endpoint")
      val actions = EndpointActions(newApp, Some(currentApp), namespace)
      actions.size mustBe 1
      val createActions = actions.collect { case a: CreateOrUpdateAction[_] ⇒ a }

      val services = createActions.map(_.resource).collect {
        case service: Service ⇒ service
      }

      services.size mustBe 1
      services.foreach { service ⇒
        assertService(service, newApp.spec)
      }
    }
  }

  def assertService(service: Service, app: CloudflowApplication.Spec) = {
    val deployment = app.deployments.find(deployment ⇒ Name.ofService(deployment.name) == service.metadata.name).value
    val endpoint   = deployment.endpoint.value
    service.metadata.name mustEqual Name.ofService(deployment.name)
    service.metadata.namespace mustEqual namespace
    val serviceSelector: Option[Map[String, String]] = service.spec.map(spec ⇒ spec.selector)
    serviceSelector.value(CloudflowLabels.Name) mustEqual Name.ofPod(deployment.name)
    service.spec.value._type mustEqual Service.Type.ClusterIP
    val ports = service.spec.value.ports
    ports must have size 1

    forAll(ports) { port ⇒
      port.port mustEqual endpoint.containerPort
      port.name mustEqual Name.ofContainerPort(endpoint.containerPort)
      port.targetPort.value mustEqual Right(Name.ofContainerPort(endpoint.containerPort))
    }
  }

}
