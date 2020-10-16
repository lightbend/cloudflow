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

import java.util.Collections

import akka.actor.ActorSystem
import cloudflow.blueprint.Blueprint
import cloudflow.blueprint.deployment._
import cloudflow.operator.event.ConfigInputChangeEvent
import com.typesafe.config.Config
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.CreateTopicsOptions
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.KafkaFuture
import org.slf4j.LoggerFactory
import play.api.libs.json.Format
import skuber._
import skuber.api.client.KubernetesClient
import skuber.json.format._

import scala.collection.immutable._
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.jdk.CollectionConverters._

/**
 * Creates topic actions for managed topics.
 */
object TopicActions {

  private val log = LoggerFactory.getLogger(TopicActions.getClass)

  def apply(newApp: CloudflowApplication.CR)(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    def distinctTopics(app: CloudflowApplication.Spec): Set[TopicInfo] =
      app.deployments.flatMap(_.portMappings.values.filter(_.managed).map(topic => TopicInfo(topic))).toSet

    def deploymentOf(topicId: String)(deployment: StreamletDeployment) = deployment.portMappings.values.exists(_.id == topicId)

    val managedTopics = distinctTopics(newApp.spec)

    val labels = CloudflowLabels(newApp)
    val actions =
      managedTopics.toVector.map { topic =>
        // find a config (secret) for the topic, all deployments should have the same configuration for the same topic,
        // since topics are defined once, deployments refer to the topics by port mappings.
        // So it is ok to just take the first one found, that uses the topic.
        val appConfigSecretName = newApp.spec.deployments
          .filter(deploymentOf(topic.id))
          .headOption
          .map(_.secretName)

        action(appConfigSecretName, newApp.namespace, labels, topic, newApp)
      }
    actions
  }

  type TopicResource = ConfigMap
  val DefaultConfigurationName = "default"
  val KafkaClusterNameFormat   = "cloudflow-kafka-cluster-%s"
  val KafkaClusterNameLabel    = "cloudflow.lightbend.com/kafka-cluster-name"

  /**
   * Create a topic using the correct Kafka configuration.
   *
   * Configuration resolution order:
   * 1. App secret contains inline connection configuration for topic
   * 2. User-defined topic cluster configuration name in blueprint
   * 3. Default topic cluster configuration
   */
  def action(appConfigSecretName: Option[String],
             namespace: String,
             labels: CloudflowLabels,
             topic: TopicInfo,
             newApp: CloudflowApplication.CR)(
      implicit ctx: DeploymentContext
  ): Action[ObjectResource] = {
    def useClusterConfiguration(providedTopic: TopicInfo): Action[ObjectResource] =
      providedTopic.cluster
        .map { cluster =>
          Action.provided[Secret, ObjectResource](
            String.format(KafkaClusterNameFormat, cluster),
            ctx.podNamespace, {
              case Some(secret) => createActionFromKafkaConfigSecret(secret, namespace, labels, providedTopic)
              case None =>
                val msg = s"Could not find Kafka configuration for topic [${providedTopic.name}] cluster [$cluster]"
                log.error(msg)
                CloudflowApplication.Status.errorAction(newApp, msg)
            }
          )
        }
        .getOrElse {
          if (topic.cluster.eq(Some(DefaultConfigurationName))) {
            val msg =
              "A default Kafka configuration was not defined during installation of cloudflow-operator. Cannot create managed topics."
            log.error(msg)
            CloudflowApplication.Status.errorAction(newApp, msg)
          } else {
            useClusterConfiguration(topic.copy(cluster = Some(DefaultConfigurationName)))
          }
        }

    appConfigSecretName
      .map { name =>
        Action.provided[Secret, ObjectResource](
          name,
          namespace, { secretOption =>
            maybeCreateActionFromAppConfigSecret(secretOption, namespace, labels, topic)
              .getOrElse(useClusterConfiguration(topic))
          }
        )
      }
      .getOrElse(useClusterConfiguration(topic))
  }

  def createActionFromKafkaConfigSecret(secret: Secret, namespace: String, labels: CloudflowLabels, topic: TopicInfo) = {
    val config    = ConfigInputChangeEvent.getConfigFromSecret(secret)
    val topicInfo = TopicInfo(Topic(id = topic.id, cluster = topic.cluster, config = config))
    createAction(namespace, labels, topicInfo)
  }

  def maybeCreateActionFromAppConfigSecret(secretOption: Option[Secret], namespace: String, labels: CloudflowLabels, topic: TopicInfo) =
    for {
      secret <- secretOption
      config = ConfigInputChangeEvent.getConfigFromSecret(secret)
      kafkaConfig <- getKafkaConfig(config, topic)
      topicWithKafkaConfig = TopicInfo(Topic(id = topic.id, config = kafkaConfig))
      _ <- topicWithKafkaConfig.bootstrapServers
    } yield createAction(namespace, labels, topicWithKafkaConfig)

  def createAction(appNamespace: String, labels: CloudflowLabels, topic: TopicInfo): CreateOrUpdateAction[ConfigMap] = {
    val (bootstrapServers, partitions, replicas, brokerConfig) = (topic.bootstrapServers, topic.partitions, topic.replicationFactor) match {
      case (Some(bootstrapServers), Some(partitions), Some(replicas)) => (bootstrapServers, partitions, replicas, topic.brokerConfig)
      case _ =>
        throw new Exception(
          s"Default Kafka connection configuration was invalid for topic [${topic.name}]" +
              topic.cluster.map(c => s", cluster [$c]").getOrElse("") +
              ". Update installation of Cloudflow with Helm charts to include a default Kafka cluster configuration that contains defaults for 'bootstrapServers', 'partitions', and 'replicas'."
        )
    }
    val configMap   = resource(appNamespace, topic, partitions, replicas, bootstrapServers, labels)
    val adminClient = KafkaAdmins.getOrCreate(bootstrapServers, brokerConfig)

    new CreateOrUpdateAction[ConfigMap](configMap, implicitly[Format[ConfigMap]], implicitly[ResourceDefinition[ConfigMap]], editor) {
      override def execute(
          client: KubernetesClient
      )(implicit sys: ActorSystem, ec: ExecutionContext, lc: skuber.api.client.LoggingContext): Future[Action[ConfigMap]] =
        super.execute(client).flatMap { resourceCreatedAction =>
          createTopic().map(_ => resourceCreatedAction)
        }

      private def topicExists(name: String)(implicit executionContext: ExecutionContext) =
        adminClient.listTopics().namesToListings().asScala.map(_.asScala.toMap).map(_.contains(name))

      private def createTopic()(implicit ec: ExecutionContext) =
        topicExists(topic.name).flatMap { exists =>
          if (exists) {
            log.info("Managed topic [{}] exists already, ignoring", topic.name)
            Future.successful(akka.Done)
          } else {
            log.info("Creating managed topic [{}]", topic.name)
            val newTopic = new NewTopic(topic.name, partitions, replicas.toShort).configs(topic.properties.asJava)
            val result =
              adminClient.createTopics(
                Collections.singleton(newTopic),
                new CreateTopicsOptions()
              )
            result.all().asScala.map(_ => akka.Done)
          }
        }
    }
  }

  def resource(namespace: String,
               topic: TopicInfo,
               partitions: Int,
               replicationFactor: Int,
               bootstrapServers: String,
               labels: CloudflowLabels): ConfigMap =
    ConfigMap(
      metadata = ObjectMeta(name = s"topic-${topic.id}", labels = labels(topic.id), namespace = namespace),
      data = Map(
          "id"                -> topic.id,
          "name"              -> topic.name,
          "partitions"        -> partitions.toString,
          "replicationFactor" -> replicationFactor.toString,
          "bootstrap.servers" -> bootstrapServers
        ) ++ topic.properties
    )

  private val editor = new ObjectEditor[ConfigMap] {
    override def updateMetadata(obj: ConfigMap, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
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
    def apply(t: Topic): TopicInfo = TopicInfo(
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
      Topic.pathAsMap(t.config, Blueprint.ConnectionConfigKey)
    )

    private def intOrEmpty(config: Config, key: String): Option[Int] =
      if (config.hasPath(key)) Some(config.getInt(key)) else None
    private def stringOrEmpty(config: Config, key: String): Option[String] =
      if (config.hasPath(key)) Some(config.getString(key)) else None
  }

  case class TopicInfo(id: String,
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
      if (admins.contains(bootstrapServers)) admins(bootstrapServers)
      else {
        val conf = brokerConfig + (AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServers)
        val a    = Admin.create(conf.asJava)
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
      val promise = Promise[T]
      fut.whenComplete { (res, error) =>
        if (error == null) promise.success(res)
        else promise.failure(error)
      }
      promise.future
    }
  }
}
