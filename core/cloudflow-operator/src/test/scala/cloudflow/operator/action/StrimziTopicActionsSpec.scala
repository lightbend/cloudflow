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

class StrimziTopicActionsSpec extends WordSpec with MustMatchers with GivenWhenThen with EitherValues with TestDeploymentContext {
  case class Foo(name: String)
  case class Bar(name: String)
  val namespace  = "ns"
  val appVersion = "0.0.1"
  val image      = "image-1"
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

  "StrimziTopicActions" should {
    "create topics when there is no previous application deployment" in {

      Given("no current app")
      val newApp = createApp()

      When("savepoint actions are created from a new app")
      val actions = StrimziTopicActions(
        newApp,
        None,
        ctx.kafkaContext.strimziTopicOperatorNamespace.get,
        ctx.kafkaContext.strimziClusterName.get,
        ctx.kafkaContext.partitionsPerTopic,
        ctx.kafkaContext.replicationFactor,
        false
      )

      Then("only create topic actions must be created between the streamlets")
      val createActions = actions.collect { case c: CreateOrUpdateAction[_] ⇒ c }
      val topics        = newApp.spec.deployments.flatMap(_.portMappings.values).distinct

      createActions.size mustBe actions.size
      // topics must be created to connect ingress, processor, egress
      createActions.size mustBe newApp.spec.deployments.flatMap(d => d.portMappings.values.map(_.name)).distinct.size
      topics.foreach { topic ⇒
        val resource = createActions
          .find(_.resource.metadata.name == topic.name)
          .value
          .resource
          .asInstanceOf[StrimziTopicActions.TopicResource]
        assertTopic(topic, resource, newApp.spec.appId)
      }
    }

    "do nothing when the new applications requires the same savepoints as the current one" in {

      Given("a current app")
      val newApp     = createApp()
      val currentApp = Some(newApp)

      When("nothing changes in the new app")
      val actions = StrimziTopicActions(
        newApp,
        currentApp,
        ctx.kafkaContext.strimziTopicOperatorNamespace.get,
        ctx.kafkaContext.strimziClusterName.get,
        ctx.kafkaContext.partitionsPerTopic,
        ctx.kafkaContext.replicationFactor,
        false
      )

      Then("no actions should be created")
      actions mustBe empty
    }

    "delete topics if deleteExistingTopics is set and the new app removes savepoints" in {

      Given("a current app, ingress -> processor -> egress")
      val ingress   = randomStreamlet().asIngress[Foo].withServerAttribute
      val processor = randomStreamlet().asProcessor[Foo, Bar].withRuntime("spark")
      val egress    = randomStreamlet().asEgress[Bar].withServerAttribute

      val ingressRef   = ingress.ref("ingress")
      val processorRef = processor.ref("processor")
      val egressRef    = egress.ref("egress")
      val bp = Blueprint()
        .define(Vector(ingress, processor, egress))
        .use(ingressRef)
        .use(processorRef)
        .use(egressRef)
        .connect(BTopic("foos"), ingressRef.out, processorRef.in)
        .connect(BTopic("bars"), processorRef.out, egressRef.in)

      val verifiedBlueprint = bp.verified.right.value
      val appId             = "monstrous-mite-12345"
      val appVersion        = "42-abcdef0"
      val newAppVersion     = "43-abcdef0"
      val image             = "image-1"
      val currentApp        = CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths))

      val deployedTopic = currentApp.spec.deployments.find(_.streamletName == "processor").value.portMappings(processor.out.name)

      When("the new app removes the processor and the egress")
      val newBp =
        bp.disconnect(egressRef.in)
          .remove(egressRef.name)
          .disconnect(processorRef.in)
          .remove(processorRef.name)
      val newApp =
        CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, newAppVersion, image, newBp.verified.right.value, agentPaths))
      val actions = StrimziTopicActions(
        newApp,
        Some(currentApp),
        ctx.kafkaContext.strimziTopicOperatorNamespace.get,
        ctx.kafkaContext.strimziClusterName.get,
        ctx.kafkaContext.partitionsPerTopic,
        ctx.kafkaContext.replicationFactor,
        true
      )

      Then("one delete action should be created for the processor outlet savepoint")
      actions.size mustBe 1
      val resourceName = actions(0).resourceName
      resourceName mustBe deployedTopic.name
      actions(0) mustBe a[DeleteAction[_]]

      When("deleteExistingTopics is set to false")
      val noActions = StrimziTopicActions(
        newApp,
        Some(currentApp),
        ctx.kafkaContext.strimziTopicOperatorNamespace.get,
        ctx.kafkaContext.strimziClusterName.get,
        ctx.kafkaContext.partitionsPerTopic,
        ctx.kafkaContext.replicationFactor,
        false
      )
      Then("no delete actions should be created")
      noActions mustBe empty
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

      val verifiedBlueprint = bp.verified.right.value

      val appId      = "monstrous-mite-12345"
      val appVersion = "42-abcdef0"
      val image      = "image-1"
      val currentApp = CloudflowApplication(CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths))

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
      val savepoint = newApp.spec.deployments.find(_.streamletName == "processor").value.portMappings(processor.out.name)

      Then("one create action should be created for the new savepoint between processor and egress")
      val actions = StrimziTopicActions(
        newApp,
        Some(currentApp),
        ctx.kafkaContext.strimziTopicOperatorNamespace.get,
        ctx.kafkaContext.strimziClusterName.get,
        ctx.kafkaContext.partitionsPerTopic,
        ctx.kafkaContext.replicationFactor,
        true
      )
      val resource = actions(0).asInstanceOf[ResourceAction[_]].resource.asInstanceOf[StrimziTopicActions.TopicResource]

      resource mustBe StrimziTopicActions.resource(
        StrimziTopicActions.TopicInfo(savepoint),
        CloudflowLabels(newApp),
        ctx.kafkaContext.strimziTopicOperatorNamespace.get,
        ctx.kafkaContext.strimziClusterName.get,
        ctx.kafkaContext.partitionsPerTopic,
        ctx.kafkaContext.replicationFactor
      )
      actions(0) mustBe a[CreateOrUpdateAction[_]]
      assertTopic(savepoint, resource, appId)
    }
  }

  def assertTopic(savepoint: cloudflow.blueprint.deployment.Topic, resource: StrimziTopicActions.TopicResource, appId: String)(
      implicit ctx: DeploymentContext
  ) = {
    resource.metadata.namespace mustBe ctx.kafkaContext.strimziTopicOperatorNamespace.get
    resource.metadata.name mustBe savepoint.name
    resource.spec.partitions mustBe ctx.kafkaContext.partitionsPerTopic
    resource.spec.replicas mustBe ctx.kafkaContext.replicationFactor
    resource.apiVersion mustBe "kafka.strimzi.io/v1beta1"
    resource.metadata.labels("strimzi.io/cluster") mustBe Name.ofLabelValue(ctx.kafkaContext.strimziClusterName.get)
    resource.metadata.labels("app.kubernetes.io/name") mustBe Name.ofLabelValue(savepoint.name)
    resource.metadata.labels("app.kubernetes.io/part-of") mustBe Name.ofLabelValue(appId)
    resource.metadata.labels("app.kubernetes.io/managed-by") mustBe "cloudflow"
    resource.kind mustBe "KafkaTopic"
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
  }
}
