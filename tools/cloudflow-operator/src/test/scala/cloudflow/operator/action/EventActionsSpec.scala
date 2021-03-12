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
import akka.kube.actions.CreateOrReplaceAction
import cloudflow.blueprint.BlueprintBuilder._
import cloudflow.blueprint._
import cloudflow.operator.action.runner.AkkaRunner
import cloudflow.operator.event.AppEvent
import io.fabric8.kubernetes.api.model.Event
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{ EitherValues, GivenWhenThen, Inspectors }

class EventActionsSpec
    extends AnyWordSpec
    with Matchers
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
      val appCr = App.Cr(spec = app, metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("Event actions are created from a new app")
      val actions = EventActions.deployEvents(appCr, None, runners, ctx.podNamespace, AppEvent.toObjectReference(appCr))

      Then("One event should be created")
      actions.size mustBe 1

      Then("An ApplicationDeploy event should be created")
      actions
        .collect { case a: CreateOrReplaceAction[Event] => a }
        .count(_.resource.getReason.contains("ApplicationDeployed")) mustBe 1
    }

    "create event resources for an updated app that's already been deployed" in {
      Given("a new app")
      val appCr = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)
      val currentApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)

      val currentAppCr = App.Cr(spec = currentApp, metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("Event actions are created from a new app")
      val actions = EventActions.deployEvents(
        appCr,
        Some(currentAppCr),
        runners,
        ctx.podNamespace,
        AppEvent.toObjectReference(currentAppCr))

      Then("One event should be created")
      actions.size mustBe 1

      Then("An ApplicationDeploy event should be created")
      actions
        .collect { case a: CreateOrReplaceAction[Event] => a }
        .count(_.resource.getReason.contains("ApplicationUpdated")) mustBe 1
    }

    "create event resources for an already deployed app with scaled streamlets" in {
      Given("a current app and a new app")
      val currentApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)
      val app =
        App.Cr(
          spec = currentApp.copy(deployments =
            currentApp.deployments.map(deployment => deployment.copy(replicas = Some(2)))),
          metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      val currentAppCr = App.Cr(spec = currentApp, metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("Event actions are created from a new app")
      val actions = EventActions.deployEvents(
        app,
        Some(currentAppCr),
        runners,
        ctx.podNamespace,
        AppEvent.toObjectReference(currentAppCr))

      Then("Three events should be created")
      actions.size mustBe 3

      Then("An ApplicationDeploy event should be created")
      actions
        .collect { case a: CreateOrReplaceAction[Event] => a }
        .count(_.resource.getReason.contains("ApplicationUpdated")) mustBe 1

      Then("Two StreamletScaled events should be created")
      actions
        .collect { case a: CreateOrReplaceAction[Event] => a }
        .count(_.resource.getReason.contains("StreamletScaled")) mustBe 2
    }

    "create event resources when streamlet configuration changes" in {
      Given("a current app")
      val currentApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)
      val currentAppCr = App.Cr(spec = currentApp, metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("Event actions are created for a streamlet")
      val action =
        EventActions.streamletChangeEvent(
          currentAppCr,
          currentApp.deployments.head,
          ctx.podName,
          AppEvent.toObjectReference(currentAppCr))

      Then("An StreamletConfigurationChanged event should be created")
      action
        .asInstanceOf[CreateOrReplaceAction[Event]]
        .resource
        .getReason
        .contains("StreamletConfigurationChanged") mustBe true
    }

    "create event resources for an app that is undeployed" in {
      Given("a current app")
      val currentApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)
      val currentAppCr = App.Cr(spec = currentApp, metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("Event actions are created for a streamlet")
      val action = EventActions.undeployEvent(currentAppCr, ctx.podName, AppEvent.toObjectReference(currentAppCr))

      Then("An ApplicationUndeployed event should be created")
      action.asInstanceOf[CreateOrReplaceAction[Event]].resource.getReason.contains("ApplicationUndeployed") mustBe true
    }
  }
}
