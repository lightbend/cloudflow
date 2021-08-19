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
import akka.kube.actions.{ CompositeAction, CreateOrReplaceAction, GetAction, OperatorAction }
import cloudflow.blueprint.BlueprintBuilder._
import cloudflow.blueprint.{ Topic => BTopic, _ }
import cloudflow.operator.action.runner.Base64Helper
import io.fabric8.kubernetes.api.model.{ Secret, SecretBuilder }
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{ EitherValues, GivenWhenThen, OptionValues }

import scala.jdk.CollectionConverters._
import scala.util.{ Success, Try }

class TopicActionsSpec
    extends AnyWordSpec
    with Matchers
    with GivenWhenThen
    with EitherValues
    with OptionValues
    with TestDeploymentContext {
  case class Foo(name: String)
  case class Bar(name: String)
  val namespace = "ns"
  val appVersion = "0.0.1"
  val image = "image-1"
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")
  val defaultBootstrapServers = "localhost:9092"
  val defaultPartitions = 3
  val defaultReplicas = 1
  val defaultClusterSecret = {
    new SecretBuilder()
      .withData(Map("secret.conf" ->
      Base64Helper.encode(s"""
             |bootstrap.servers = "$defaultBootstrapServers"
             |partitions = "$defaultPartitions"
             |replicas = "$defaultReplicas"
             |""".stripMargin)).asJava)
      .build()
  }

  "TopicActions" should {
    "create topics when there is no previous application deployment" in {

      Given("no current app")
      val newApp = createApp()

      When("savepoint actions are created from a new app")
      val actions = TopicActions(newApp, runners, ctx.podNamespace)

      Then("only create topic actions must be created between the streamlets")
      val createActions =
        actions.collect {
          case p: GetAction[Secret] =>
            // try to get Kafka connection info from empty application secret
            val fallbackProvidedAction = p
              .getAction(None)
              .asInstanceOf[GetAction[Secret]]

            // assert that we fallback to provide the 'default' cluster secret
            fallbackProvidedAction.resourceName mustBe TopicActions.KafkaClusterNameFormat.format(
              TopicActions.DefaultConfigurationName)

            // fallback to get Kafka connection info from 'default' cluster secret
            fallbackProvidedAction
              .getAction(Option(defaultClusterSecret))
              .asInstanceOf[CompositeAction[_]]
              .actions
              .collect { case act: CreateOrReplaceAction[TopicActions.TopicResource] => act }
              .head
        }
      val topics = newApp.spec.deployments.flatMap(_.portMappings.values).distinct
      createActions.size mustBe actions.size
      // topics must be created to connect ingress, processor, egress
      createActions.size mustBe newApp.spec.deployments.flatMap(d => d.portMappings.values.map(_.id)).distinct.size
      topics.foreach { topic =>
        val resource = createActions
          .find(_.resource.getMetadata.getName == s"topic-${topic.id}")
          .headOption
          .value
          .resource
          .asInstanceOf[TopicActions.TopicResource]
        assertTopic(topic, resource, newApp.spec.appId)
      }
    }

    "create a new topic when a savepoint is added" in {

      Given("a current app with just an ingress")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val processor = randomStreamlet().asProcessor[Foo, Bar].withRuntime("spark")
      val egress = randomStreamlet().asEgress[Bar].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val bp = Blueprint()
        .define(Vector(ingress, processor, egress))
        .use(ingressRef)
        .connect(BTopic("foos"), ingressRef.out)

      val appId = "monstrous-mite-12345"
      val image = "image-1"

      When("the new app adds a savepoint, ingress -> processor -> egress")
      val processorRef = processor.ref("processor")
      val egressRef = egress.ref("egress")
      val newBp = bp
        .use(processorRef)
        .use(egressRef)
        .connect(BTopic("foos"), processorRef.in)
        .connect(BTopic("bars"), processorRef.out, egressRef.in)
      val newAppVersion = "43-abcdef0"
      val metadata = CloudflowApplicationSpecBuilder.demoMetadata
      metadata.setNamespace("testing-app")
      val newApp =
        App.Cr(
          spec =
            CloudflowApplicationSpecBuilder.create(appId, newAppVersion, image, newBp.verified.right.value, agentPaths),
          metadata = metadata)
      val in = newApp.spec.deployments.find(_.streamletName == "processor").value.portMappings(processor.in.name)
      val savepoint =
        newApp.spec.deployments.find(_.streamletName == "processor").value.portMappings(processor.out.name)

      Then("create actions for both topics should be created for the new savepoint between processor and egress")
      val Seq(foosAction, barsAction) = TopicActions(newApp, runners, ctx.podNamespace)

      val configMap0 = foosAction
        .asInstanceOf[GetAction[Secret]]
        // try to get Kafka connection info from empty application secret
        .getAction(None)
        .asInstanceOf[GetAction[Secret]]
        // fallback to get Kafka connection info from 'default' cluster secret
        .getAction(Option(defaultClusterSecret))
        .asInstanceOf[CompositeAction[_]]
        .actions
        .collect { case act: CreateOrReplaceAction[TopicActions.TopicResource] => act }
        .head
        .resource

      configMap0 mustBe TopicActions.resource(
        newApp.namespace,
        TopicActions.TopicInfo(TopicActions.portMappingToTopic(in)),
        defaultPartitions,
        defaultReplicas,
        defaultBootstrapServers,
        CloudflowLabels(newApp))
      foosAction mustBe a[GetAction[Secret]]

      val configMap1 = barsAction
        .asInstanceOf[GetAction[Secret]]
        // try to get Kafka connection info from empty application secret
        .getAction(None)
        .asInstanceOf[GetAction[Secret]]
        // fallback to get Kafka connection info from 'default' cluster secret
        .getAction(Option(defaultClusterSecret))
        .asInstanceOf[CompositeAction[_]]
        .actions
        .collect { case act: CreateOrReplaceAction[TopicActions.TopicResource] => act }
        .head
        .resource

      configMap1 mustBe TopicActions.resource(
        newApp.namespace,
        TopicActions.TopicInfo(TopicActions.portMappingToTopic(savepoint)),
        defaultPartitions,
        defaultReplicas,
        defaultBootstrapServers,
        CloudflowLabels(newApp))
      barsAction mustBe a[GetAction[Secret]]
      assertTopic(savepoint, configMap1, appId)
    }

    "create a topic for a named kafka cluster" in {
      val clusterName = "cluster-baz"
      val clusterSecret = {
        new SecretBuilder()
          .withData(Map("secret.conf" ->
          Base64Helper.encode(s"""
               |bootstrap.servers = "localhost:19092"
               |partitions = "100"
               |replicas = "3"
               |""".stripMargin)).asJava)
          .build()
      }

      Given("no current app")
      val newApp = createApp(Option(clusterName))

      When("savepoint actions are created from a new app")
      val actions = TopicActions(newApp, runners, ctx.podNamespace)

      Then("only create topic actions must be created between the streamlets")
      val createActions =
        actions.collect {
          case p: GetAction[Secret] =>
            // try to get Kafka connection info from empty application secret
            val fallbackProvidedAction = p
              .getAction(None)
              .asInstanceOf[GetAction[Secret]]

            // assert that we fallback to provide the 'cluster-baz' cluster secret as specified in topic config
            fallbackProvidedAction.resourceName mustBe TopicActions.KafkaClusterNameFormat.format(clusterName)

            // fallback to get Kafka connection info from 'cluster-baz' cluster secret
            fallbackProvidedAction
              .getAction(Option(clusterSecret))
              .asInstanceOf[CompositeAction[_]]
              .actions
              .collect { case act: CreateOrReplaceAction[TopicActions.TopicResource] => act }
              .head
        }
      val topics = newApp.spec.deployments.flatMap(_.portMappings.values).distinct
      createActions.size mustBe actions.size
      // topics must be created to connect ingress, processor, egress
      createActions.size mustBe newApp.spec.deployments.flatMap(d => d.portMappings.values.map(_.id)).distinct.size
      topics.foreach { topic =>
        val resource = createActions
          .find(_.resource.getMetadata.getName == s"topic-${topic.id}")
          .value
          .resource
          .asInstanceOf[TopicActions.TopicResource]
        assertTopic(
          topic,
          resource,
          newApp.spec.appId,
          bootstrapServers = "localhost:19092",
          partitions = 100,
          replicas = 3)
      }
    }
  }

  def assertTopic(
      savepoint: App.PortMapping,
      resource: TopicActions.TopicResource,
      appId: String,
      bootstrapServers: String = defaultBootstrapServers,
      partitions: Int = defaultPartitions,
      replicas: Int = defaultReplicas) = {
    resource.getMetadata.getNamespace mustBe "testing-app"
    resource.getMetadata.getName mustBe s"topic-${savepoint.id}"
    resource.getData.get("bootstrap.servers") mustBe bootstrapServers
    resource.getData.get("partitions") mustBe partitions.toString
    resource.getData.get("replicationFactor") mustBe replicas.toString
    resource.getApiVersion mustBe "v1"
    resource.getMetadata.getLabels.get("app.kubernetes.io/name") mustBe Name.ofLabelValue(savepoint.id)
    resource.getMetadata.getLabels.get("app.kubernetes.io/part-of") mustBe Name.ofLabelValue(appId)
    resource.getMetadata.getLabels.get("app.kubernetes.io/managed-by") mustBe "cloudflow"
    resource.getKind mustBe "ConfigMap"
  }

  def createApp(cluster: Option[String] = None) = {
    val rndStreamlet = randomStreamlet()
    val fooIngress = rndStreamlet.asIngress[Foo]

    val ingress = fooIngress.withServerAttribute
    val processor = randomStreamlet().asProcessor[Foo, Bar].withRuntime("spark")
    val egress = randomStreamlet().asEgress[Bar].withServerAttribute

    val ingressRef = ingress.ref("ingress")
    val processorRef = processor.ref("processor")
    val egressRef = egress.ref("egress")

    val verifiedBlueprint = Blueprint()
      .define(Vector(ingress, processor, egress))
      .use(ingressRef)
      .use(processorRef)
      .use(egressRef)
      .connect(BTopic(id = "foos", cluster = cluster), ingressRef.out, processorRef.in)
      .connect(BTopic(id = "bars", cluster = cluster), processorRef.out, egressRef.in)
      .verified
      .right
      .value

    val appId = "monstrous-mite-12345"
    val appVersion = "42-abcdef0"
    val image = "image-1"

    val metadata = CloudflowApplicationSpecBuilder.demoMetadata
    metadata.setNamespace("testing-app")

    App.Cr(
      spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
      metadata = metadata)
  }
}
