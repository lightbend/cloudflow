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
import com.typesafe.config._

import scala.collection.immutable._
import scala.collection.JavaConverters._
import scala.concurrent._

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.{ Config => KConfig }
import org.apache.kafka.clients.admin.ListTopicsOptions
import org.apache.kafka.clients.admin.ListTopicsResult
import org.apache.kafka.clients.admin.NewPartitions
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.TopicDescription

import play.api.libs.json.Format

import skuber._
import skuber.api.client._
import skuber.json.format._

import cloudflow.blueprint.deployment._
import cloudflow.blueprint.Blueprint

/**
 * Creates a sequence of resource actions for the topic changes
 * between a current application and a new application.
 * creates ConfigMaps for kafka topics and executes changes using Kafka AdminClients.
 */
object KafkaTopicActions {
  def apply(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR],
      // TODO should this be supported?
      deleteOutdatedTopics: Boolean
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    def distinctTopics(app: CloudflowApplication.Spec): Map[String, TopicInfo] =
      app.deployments
        .flatMap(_.portMappings.values.map { sp =>
          sp.name -> TopicInfo(sp)
        })
        .toMap

    val labels = CloudflowLabels(newApp)

    val currentTopics = currentApp.map(cr => distinctTopics(cr.spec)).getOrElse(Map.empty[String, TopicInfo])
    val newTopics     = distinctTopics(newApp.spec)

    val updateActions = newTopics.flatMap {
      case (name, topicInfo) =>
        currentTopics.get(name).flatMap { currentTopic =>
          if (currentTopic != topicInfo) Some(updateAction(labels)(topicInfo)) else None
        }
    }.toVector

    val createActions =
      (newTopics -- currentTopics.keys).values.toVector
        .flatMap(topic => if (topic.managed) Some(createAction(labels)(topic)) else None)
    updateActions ++ createActions
  }

  def createAction(labels: CloudflowLabels)(topic: TopicInfo)(implicit ctx: DeploymentContext) =
    new CreateAction[ConfigMap](resource(topic, labels), implicitly[Format[ConfigMap]], implicitly[ResourceDefinition[ConfigMap]], editor) {
      override def execute(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[Action[ConfigMap]] =
        super.execute(client).flatMap { resourceCreatedAction =>
          // TODO add AdminClient calls.
          Future.successful(resourceCreatedAction)
        }
    }
  def updateAction(labels: CloudflowLabels)(topic: TopicInfo)(implicit ctx: DeploymentContext) =
    new UpdateAction[ConfigMap](resource(topic, labels), implicitly[Format[ConfigMap]], implicitly[ResourceDefinition[ConfigMap]], editor) {
      override def execute(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[Action[ConfigMap]] =
        super.execute(client).flatMap { resourceUpdatedAction =>
          // TODO add AdminClient calls.
          Future.successful(resourceUpdatedAction)
        }
    }

  def resource(topic: TopicInfo, labels: CloudflowLabels)(
      implicit ctx: DeploymentContext
  ): ConfigMap = {
    val defaultPartitions = ctx.kafkaContext.partitionsPerTopic
    val defaultReplicas   = ctx.kafkaContext.replicationFactor

    val metadata = ObjectMeta(name = s"topic-${topic.name}", labels = labels(topic.name))

    ConfigMap(
      metadata = metadata,
      data = Map(
          "name"       -> topic.name,
          "partitions" -> topic.partitions.getOrElse(defaultPartitions).toString,
          "replicas"   -> topic.replicas.getOrElse(defaultReplicas).toString
        ) ++ topic.configMap
    )
  }

  private val editor = new ObjectEditor[ConfigMap] {
    override def updateMetadata(obj: ConfigMap, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }

  object TopicInfo {
    def apply(topic: Topic): TopicInfo = TopicInfo(
      topic.name,
      intOrEmpty(topic.config, Blueprint.PartitionsKey),
      intOrEmpty(topic.config, Blueprint.ReplicasKey),
      topicConfigMap(topic.config),
      topic.managed
    )
    private def intOrEmpty(config: Config, key: String): Option[Int] =
      if (config.hasPath(key)) Some(config.getInt(key)) else None

    private def topicConfigMap(config: Config): Map[String, String] =
      if (config.hasPath(Blueprint.TopicConfigKey)) {
        config
          .getConfig(Blueprint.TopicConfigKey)
          .entrySet()
          .asScala
          .map(entry => entry.getKey -> entry.getValue.unwrapped().toString)
          .toMap
      } else Map.empty[String, String]
  }

  case class TopicInfo(
      name: String,
      partitions: Option[Int],
      replicas: Option[Int],
      configMap: Map[String, String],
      managed: Boolean
  )

  // turn into an 'action', if it is done, or failed, close kafka admin client.
  class KafkaAdmin(adminClient: AdminClient, defaultPartitions: Int, defaultReplicas: Int) {

    def createTopic(topicInfo: TopicInfo): Unit = {
      val newTopic =
        new NewTopic(topicInfo.name,
                     topicInfo.partitions.getOrElse(defaultPartitions),
                     topicInfo.replicas.getOrElse(defaultReplicas).toShort)
          .configs(topicInfo.configMap.asJava)
      // TODO improve KafkaFuture handling
      adminClient.createTopics(Vector(newTopic).asJava).values().get(newTopic.name()).get
    }
    def deleteTopic(topicName: String): Unit = {
      // TODO improve KafkaFuture handling
      adminClient.deleteTopics(Vector(topicName).asJava).values().get(topicName).get
      // TODO on completion do a describe, if it is not there, ok, otherwise fail.
      ()
    }

    //
    def updateTopicConfig(topicInfo: TopicInfo): Unit = {
      // TODO
    }

    // if new partitions > old partitions, can't decrease partitions.
    def increasePartitions(topic: TopicInfo): Unit = {
      // TODO
    }
    def close = adminClient.close()
  }
}
