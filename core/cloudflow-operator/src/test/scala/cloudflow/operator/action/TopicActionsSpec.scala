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

import cloudflow.blueprint.{ Topic => BTopic, _ }
import BlueprintBuilder._

class TopicActionsSpec extends WordSpec with MustMatchers with GivenWhenThen with EitherValues with TestDeploymentContext {
  case class Foo(name: String)
  case class Bar(name: String)
  val namespace  = "ns"
  val appVersion = "0.0.1"
  val image      = "image-1"
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

  "TopicActions" should {
    "create topics when there is no previous application deployment" in {

      Given("no current app")
      val newApp = createApp()

      When("savepoint actions are created from a new app")
      val actions = TopicActions(newApp)

      Then("only create topic actions must be created between the streamlets")
      val createActions = actions.collect { case c: CreateOrUpdateAction[_] ⇒ c }
      val topics        = newApp.spec.deployments.flatMap(_.portMappings.values).distinct

      createActions.size mustBe actions.size
      // topics must be created to connect ingress, processor, egress
      createActions.size mustBe newApp.spec.deployments.flatMap(d => d.portMappings.values.map(_.name)).distinct.size
      topics.foreach { topic ⇒
        val resource = createActions
          .find(_.resource.metadata.name == s"topic-${topic.name}")
          .value
          .resource
          .asInstanceOf[TopicActions.TopicResource]
        assertTopic(topic, resource, newApp.spec.appId)
      }
    }

    "create a new topic when a savepoint is added" in {

      Given("a current app with just an ingress")
      val ingress   = randomStreamlet().asIngress[Foo].withServerAttribute
      val processor = randomStreamlet().asProcessor[Foo, Bar].withRuntime("spark")
      val egress    = randomStreamlet().asEgress[Bar].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val bp = Blueprint()
        .define(Vector(ingress, processor, egress))
        .use(ingressRef)
        .connect(BTopic("foos"), ingressRef.out)

      val appId = "monstrous-mite-12345"
      val image = "image-1"

      When("the new app adds a savepoint, ingress -> processor -> egress")
      val processorRef = processor.ref("processor")
      val egressRef    = egress.ref("egress")
      val newBp = bp
        .use(processorRef)
        .use(egressRef)
        .connect(BTopic("foos"), processorRef.in)
        .connect(BTopic("bars"), processorRef.out, egressRef.in)
      val newAppVersion = "43-abcdef0"
      val newApp =
        CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, newAppVersion, image, newBp.verified.right.value, agentPaths))
          .withNamespace("testing-app")
      val in        = newApp.spec.deployments.find(_.streamletName == "processor").value.portMappings(processor.in.name)
      val savepoint = newApp.spec.deployments.find(_.streamletName == "processor").value.portMappings(processor.out.name)

      Then("create actions for both topics should be created for the new savepoint between processor and egress")
      val Seq(foosAction, barsAction) = TopicActions(newApp)
      val configMap0                  = foosAction.asInstanceOf[ResourceAction[_]].resource.asInstanceOf[TopicActions.TopicResource]
      configMap0 mustBe TopicActions.resource(newApp.namespace,
                                              TopicActions.TopicInfo(in),
                                              ctx.kafkaContext.partitionsPerTopic,
                                              ctx.kafkaContext.replicationFactor,
                                              CloudflowLabels(newApp))
      foosAction mustBe a[CreateOrUpdateAction[_]]

      val configMap1 = barsAction.asInstanceOf[ResourceAction[_]].resource.asInstanceOf[TopicActions.TopicResource]

      configMap1 mustBe TopicActions.resource(newApp.namespace,
                                              TopicActions.TopicInfo(savepoint),
                                              ctx.kafkaContext.partitionsPerTopic,
                                              ctx.kafkaContext.replicationFactor,
                                              CloudflowLabels(newApp))
      barsAction mustBe a[CreateOrUpdateAction[_]]
      assertTopic(savepoint, configMap1, appId)
    }
  }

  def assertTopic(savepoint: cloudflow.blueprint.deployment.Topic, resource: TopicActions.TopicResource, appId: String)(
      implicit ctx: DeploymentContext
  ) = {
    resource.metadata.namespace mustBe "testing-app"
    resource.metadata.name mustBe s"topic-${savepoint.name}"
    resource.data("partitions") mustBe ctx.kafkaContext.partitionsPerTopic.toString
    resource.data("replicationFactor") mustBe ctx.kafkaContext.replicationFactor.toShort.toString
    resource.apiVersion mustBe "v1"
    resource.metadata.labels("app.kubernetes.io/name") mustBe Name.ofLabelValue(savepoint.name)
    resource.metadata.labels("app.kubernetes.io/part-of") mustBe Name.ofLabelValue(appId)
    resource.metadata.labels("app.kubernetes.io/managed-by") mustBe "cloudflow"
    resource.kind mustBe "ConfigMap"
  }

  def createApp() = {
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
      .value

    val appId      = "monstrous-mite-12345"
    val appVersion = "42-abcdef0"
    val image      = "image-1"

    CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths))
      .withNamespace("testing-app")
  }
}
