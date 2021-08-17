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
import akka.kube.actions.Action
import cloudflow.blueprint.Blueprint
import cloudflow.blueprint.deployment._
import cloudflow.operator.action.runner.Base64Helper
import cloudflow.operator.event.ConfigInput
import com.typesafe.config.{ Config, ConfigFactory }
import io.fabric8.kubernetes.api.model.{ ConfigMap, ConfigMapBuilder, Secret }
import io.fabric8.kubernetes.client.KubernetesClient
import org.apache.kafka.clients.admin.{ Admin, AdminClientConfig, CreateTopicsOptions, NewTopic }
import org.apache.kafka.common.KafkaFuture
import org.slf4j.LoggerFactory

import java.util.Collections
import scala.collection.immutable._
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.jdk.CollectionConverters._

/**
 * Creates topic actions for managed topics.
 */
object TopicActions {

  private val log = LoggerFactory.getLogger(TopicActions.getClass)

  def portMappingToTopic(pm: App.PortMapping): Topic = {
    Topic(id = pm.id, cluster = pm.cluster, config = JsonConfig(pm.config))
  }

  def apply(newApp: App.Cr, runners: Map[String, runner.Runner[_]], namedClustersNamespace: String): Seq[Action] = {
    def distinctTopics(app: App.Spec): Set[TopicInfo] =
      app.deployments
        .flatMap(_.portMappings.values.map(portMappingToTopic).filter(_.managed).map(topic => TopicInfo(topic)))
        .toSet

    def deploymentOf(topicId: String)(deployment: App.Deployment) =
      deployment.portMappings.values.exists(_.id == topicId)

    val managedTopics = distinctTopics(newApp.spec)

    val labels = CloudflowLabels(newApp)
    val actions =
      managedTopics.toVector.map { topic =>
        // find a config (secret) for the topic, all deployments should have the same configuration for the same topic,
        // since topics are defined once, deployments refer to the topics by port mappings.
        // So it is ok to just take the first one found, that uses the topic.
        val appConfigSecretName = newApp.spec.deployments
          .find(deploymentOf(topic.id))
          .map(_.secretName)

        action(appConfigSecretName, runners, labels, topic, newApp, namedClustersNamespace)
      }
    actions
  }

  type TopicResource = ConfigMap
  val DefaultConfigurationName = "default"
  val KafkaClusterNameFormat = "cloudflow-kafka-cluster-%s"
  val KafkaClusterNameLabel = "cloudflow.lightbend.com/kafka-cluster-name"

  /**
   * Create a topic using the correct Kafka configuration.
   *
   * Configuration resolution order:
   * 1. App secret contains inline connection configuration for topic
   * 2. User-defined topic cluster configuration name in blueprint
   * 3. Default topic cluster configuration
   */
  def action(
      appConfigSecretName: Option[String],
      runners: Map[String, runner.Runner[_]],
      labels: CloudflowLabels,
      topic: TopicInfo,
      newApp: App.Cr,
      namedClustersNamespace: String): Action = {
    def useClusterConfiguration(providedTopic: TopicInfo): Action = {
      providedTopic.cluster
        .map { cluster =>
          Action.get[Secret](String.format(KafkaClusterNameFormat, cluster), namedClustersNamespace) {
            case Some(secret) => createActionFromKafkaConfigSecret(secret, newApp, runners, labels, providedTopic)
            case None =>
              val msg = s"Could not find Kafka configuration for topic [${providedTopic.name}] cluster [$cluster]"
              log.error(msg)
              CloudflowStatus.errorAction(newApp, runners, msg)
          }
        }
        .getOrElse {
          if (topic.cluster.eq(Some(DefaultConfigurationName))) {
            val msg =
              "A default Kafka configuration was not defined during installation of cloudflow-operator. Cannot create managed topics."
            log.error(msg)
            CloudflowStatus.errorAction(newApp, runners, msg)
          } else {
            useClusterConfiguration(topic.copy(cluster = Some(DefaultConfigurationName)))
          }
        }
    }

    appConfigSecretName
      .map { name =>
        Action.get[Secret](name, newApp.namespace) { secretOption =>
          maybeCreateActionFromAppConfigSecret(secretOption, newApp, runners, labels, topic)
            .getOrElse(useClusterConfiguration(topic))
        }
      }
      .getOrElse(useClusterConfiguration(topic))
  }

  def getData(secret: Secret): String =
    Option(secret.getData.get(ConfigInput.SecretDataKey)).map(Base64Helper.decode).getOrElse("")

  def getConfigFromSecret(secret: Secret): Config = {
    val str = getData(secret)
    ConfigFactory.parseString(str)
  }

  def createActionFromKafkaConfigSecret(
      secret: Secret,
      newApp: App.Cr,
      runners: Map[String, runner.Runner[_]],
      labels: CloudflowLabels,
      topic: TopicInfo) = {
    val config = getConfigFromSecret(secret)
    val topicInfo = TopicInfo(Topic(id = topic.id, cluster = topic.cluster, config = config))
    createTopicOrError(newApp, runners, labels, topicInfo)
  }

  def maybeCreateActionFromAppConfigSecret(
      secretOption: Option[Secret],
      newApp: App.Cr,
      runners: Map[String, runner.Runner[_]],
      labels: CloudflowLabels,
      topic: TopicInfo) =
    for {
      secret <- secretOption
      config = getConfigFromSecret(secret)
      kafkaConfig <- getKafkaConfig(config, topic)
      topicWithKafkaConfig = TopicInfo(Topic(id = topic.id, config = kafkaConfig))
      _ <- topicWithKafkaConfig.bootstrapServers
    } yield createTopicOrError(newApp, runners, labels, topicWithKafkaConfig)

  def createTopicOrError(
      newApp: App.Cr,
      runners: Map[String, runner.Runner[_]],
      labels: CloudflowLabels,
      topic: TopicInfo): Action =
    (topic.bootstrapServers, topic.partitions, topic.replicationFactor) match {
      case (Some(bootstrapServers), Some(partitions), Some(replicas)) =>
        createAction(topic, labels, runners, newApp, bootstrapServers, partitions, replicas)
      case _ =>
        val msg = s"Default Kafka connection configuration was invalid for topic [${topic.name}]" +
          topic.cluster.map(c => s", cluster [$c]").getOrElse("") +
          ". Update installation of Cloudflow with Helm charts to include a default Kafka cluster configuration that contains defaults for 'bootstrapServers', 'partitions', and 'replicas'."
        CloudflowStatus.errorAction(newApp, runners, msg)
    }

  private class CreateTopicAction(
      newApp: App.Cr,
      runners: Map[String, runner.Runner[_]],
      adminClient: Admin,
      topic: TopicInfo,
      partitions: Int,
      replicas: Int)(implicit val file: sourcecode.File, val lineNumber: sourcecode.Line)
      extends Action {

    val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"

    private def topicExists(name: String)(implicit executionContext: ExecutionContext) =
      adminClient.listTopics().namesToListings().asScala.map(_.asScala.toMap).map(_.contains(name))

    private def createTopic()(implicit ec: ExecutionContext) =
      topicExists(topic.name).flatMap { exists =>
        if (exists) {
          log.info("Managed topic [{}] exists already, ignoring", topic.name)
          Future.successful(Action.noop)
        } else {
          log.info("Creating managed topic [{}]", topic.name)
          val newTopic = new NewTopic(topic.name, partitions, replicas.toShort).configs(topic.properties.asJava)
          val result =
            adminClient.createTopics(Collections.singleton(newTopic), new CreateTopicsOptions())
          result.all().asScala.map(_ => Action.noop)
        }
      }

    def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Action] = {
      createTopic()
        .recoverWith {
          case t =>
            log.error(s"Error creating topic: ${t.getMessage}", t)
            CloudflowStatus.errorAction(newApp, runners, t.getMessage).execute(client)
        }
    }
  }

  def createAction(
      topic: TopicInfo,
      labels: CloudflowLabels,
      runners: Map[String, runner.Runner[_]],
      newApp: App.Cr,
      bootstrapServers: String,
      partitions: Int,
      replicas: Int) = {
    val brokerConfig = topic.brokerConfig
    val configMap = resource(newApp.namespace, topic, partitions, replicas, bootstrapServers, labels)
    val adminClient = KafkaAdmins.getOrCreate(bootstrapServers, brokerConfig)

    Action.composite(
      Seq(
        Action.createOrReplace[ConfigMap](configMap),
        new CreateTopicAction(
          newApp = newApp,
          runners = runners,
          adminClient = adminClient,
          topic = topic,
          partitions = partitions,
          replicas = replicas)))
  }

  def resource(
      namespace: String,
      topic: TopicInfo,
      partitions: Int,
      replicationFactor: Int,
      bootstrapServers: String,
      labels: CloudflowLabels): ConfigMap = {
    new ConfigMapBuilder()
      .withNewMetadata()
      .withName(Name.makeDNS1123CompatibleSubDomainName(s"topic-${topic.id}"))
      .withLabels(labels(topic.id).asJava)
      .withNamespace(namespace)
      .endMetadata()
      .withData((Map(
        "id" -> topic.id,
        "name" -> topic.name,
        "partitions" -> partitions.toString,
        "replicationFactor" -> replicationFactor.toString,
        "bootstrap.servers" -> bootstrapServers) ++ topic.properties).asJava)
      .build()
  }

  private def getKafkaConfig(config: Config, topic: TopicInfo): Option[Config] =
    if (config.hasPath(RunnerConfig.PortMappingsPath)) {
      val portMappingsConfig = config.getConfig(RunnerConfig.PortMappingsPath)
      // get the port mapping that matches the topic id.
      portMappingsConfig
        .root()
        .entrySet
        .asScala
        .map(_.getKey)
        .find { key =>
          val topicIdInConfig = portMappingsConfig.getString(s"${key}.id")
          topicIdInConfig == topic.id
        }
        .flatMap { key =>
          getConfig(portMappingsConfig, s"$key.config")
        }
    } else None

  private def getConfig(config: Config, key: String): Option[Config] =
    if (config.hasPath(key)) Some(config.getConfig(key)) else None

  object TopicInfo {
    def apply(t: Topic): TopicInfo =
      TopicInfo(
        t.id,
        t.name,
        t.cluster,
        intOrEmpty(t.config, Blueprint.PartitionsKey),
        intOrEmpty(t.config, Blueprint.ReplicasKey),
        Topic
          .pathAsMap(t.config, Blueprint.TopicConfigKey)
          // filter out config Kafka doesn't understand
          .filterNot(_._1 == "name"),
        t.managed,
        stringOrEmpty(t.config, Blueprint.BootstrapServersKey),
        Topic.pathAsMap(t.config, Blueprint.ConnectionConfigKey))

    private def intOrEmpty(config: Config, key: String): Option[Int] =
      if (config.hasPath(key)) Some(config.getInt(key)) else None
    private def stringOrEmpty(config: Config, key: String): Option[String] =
      if (config.hasPath(key)) Some(config.getString(key)) else None
  }

  case class TopicInfo(
      id: String,
      name: String,
      cluster: Option[String],
      partitions: Option[Int],
      replicationFactor: Option[Int],
      properties: Map[String, String],
      managed: Boolean,
      bootstrapServers: Option[String],
      brokerConfig: Map[String, String])

  object KafkaAdmins {
    private var admins = Map.empty[String, Admin]

    def getOrCreate(bootstrapServers: String, brokerConfig: Map[String, AnyRef]): Admin =
      if (admins.contains(bootstrapServers)) {
        admins(bootstrapServers)
      } else {
        val conf = brokerConfig + (AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServers)
        val a = Admin.create(conf.asJava)
        admins = admins + (bootstrapServers -> a)
        a
      }

    def close(timeout: Duration)(implicit ec: ExecutionContext): Future[Unit] =
      Future {
        admins.values.foreach(_.close(java.time.Duration.ofMillis(timeout.toMillis)))
      }
  }

  implicit class KafkaFutureConverter[T](fut: KafkaFuture[T]) {
    def asScala: Future[T] = {
      val promise = Promise[T]()
      fut.whenComplete { (res, error) =>
        if (error == null) promise.success(res)
        else promise.failure(error)
      }
      promise.future
    }
  }
}
