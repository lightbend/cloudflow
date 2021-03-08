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

import org.scalatest._
import cloudflow.blueprint._
import BlueprintBuilder._
import cloudflow.operator.action.runner.AkkaRunner

class EventActionsSpec
    extends WordSpec
    with MustMatchers
    with GivenWhenThen
    with EitherValues
    with Inspectors
    with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)
  val runner = new AkkaRunner(ctx.akkaRunnerDefaults)
  val namespace = "ns"
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

  val ingress = randomStreamlet().asIngress[Foo]
  val egress = randomStreamlet().asEgress[Foo]

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

  "EventActions" should {
    "create event resources for a new deployed app" in {
      Given("a new app")
      val app = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)
      val appCr = CloudflowApplication(app)

      When("Event actions are created from a new app")
      val actions = EventActions.deployEvents(appCr, None, runners, ctx.podNamespace, appCr)

      Then("One event should be created")
      actions.size mustBe 1

      Then("An ApplicationDeploy event should be created")
      actions
        .collect { case a: CreateOrUpdateAction[_] => a }
        .count(_.resource.asInstanceOf[skuber.Event].reason.contains("ApplicationDeployed")) mustBe 1
    }

    "create event resources for an updated app that's already been deployed" in {
      Given("a new app")
      val appCr = CloudflowApplication(
        CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths))
      val currentApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)

      val currentAppCr = CloudflowApplication(currentApp)

      When("Event actions are created from a new app")
      val actions = EventActions.deployEvents(appCr, Some(currentAppCr), runners, ctx.podNamespace, currentAppCr)

      Then("One event should be created")
      actions.size mustBe 1

      Then("An ApplicationDeploy event should be created")
      actions
        .collect { case a: CreateOrUpdateAction[_] => a }
        .count(_.resource.asInstanceOf[skuber.Event].reason.contains("ApplicationUpdated")) mustBe 1
    }

    "create event resources for an already deployed app with scaled streamlets" in {
      Given("a current app and a new app")
      val currentApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)
      val app =
        CloudflowApplication(
          currentApp.copy(deployments = currentApp.deployments.map(deployment => deployment.copy(replicas = Some(2)))))

      val currentAppCr = CloudflowApplication(currentApp)

      When("Event actions are created from a new app")
      val actions = EventActions.deployEvents(app, Some(currentAppCr), runners, ctx.podNamespace, currentAppCr)

      Then("Three events should be created")
      actions.size mustBe 3

      Then("An ApplicationDeploy event should be created")
      actions
        .collect { case a: CreateOrUpdateAction[_] => a }
        .count(_.resource.asInstanceOf[skuber.Event].reason.contains("ApplicationUpdated")) mustBe 1

      Then("Two StreamletScaled events should be created")
      actions
        .collect { case a: CreateOrUpdateAction[_] => a }
        .count(_.resource.asInstanceOf[skuber.Event].reason.contains("StreamletScaled")) mustBe 2
    }

    "create event resources when streamlet configuration changes" in {
      Given("a current app")
      val currentApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)
      val currentAppCr = CloudflowApplication(currentApp)

      When("Event actions are created for a streamlet")
      val action =
        EventActions.streamletChangeEvent(currentAppCr, currentApp.deployments.head, ctx.podName, currentAppCr)

      Then("An StreamletConfigurationChanged event should be created")
      action
        .asInstanceOf[CreateOrUpdateAction[_]]
        .resource
        .asInstanceOf[skuber.Event]
        .reason
        .contains("StreamletConfigurationChanged")
    }

    "create event resources for an app that is undeployed" in {
      Given("a current app")
      val currentApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)
      val currentAppCr = CloudflowApplication(currentApp)

      When("Event actions are created for a streamlet")
      val action = EventActions.undeployEvent(currentAppCr, ctx.podName, currentAppCr)

      Then("An ApplicationUndeployed event should be created")
      action
        .asInstanceOf[CreateOrUpdateAction[_]]
        .resource
        .asInstanceOf[skuber.Event]
        .reason
        .contains("ApplicationUndeployed")
    }
  }
}
