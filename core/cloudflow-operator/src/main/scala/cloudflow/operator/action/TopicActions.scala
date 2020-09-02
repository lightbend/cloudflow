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
import com.typesafe.config.Config
import org.apache.kafka.clients.admin.{ Admin, AdminClientConfig, CreateTopicsOptions, NewTopic }
import org.apache.kafka.common.KafkaFuture
import org.slf4j.LoggerFactory
import play.api.libs.json.Format
import skuber._
import skuber.api.client.KubernetesClient
import skuber.json.format._

import scala.collection.immutable._
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.jdk.CollectionConverters._

/**
 * Creates topic actions for managed topics.
 */
object TopicActions {

  private val log = LoggerFactory.getLogger(TopicActions.getClass)

  def apply(newApp: CloudflowApplication.CR)(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    def distinctTopics(app: CloudflowApplication.Spec): Set[TopicInfo] =
      app.deployments.flatMap(_.portMappings.values.filter(_.managed).map(topic => TopicInfo(topic))).toSet

    val managedTopics = distinctTopics(newApp.spec)

    val labels = CloudflowLabels(newApp)
    val actions =
      managedTopics.toVector.map(topic => createAction(newApp.namespace, labels)(topic))
    actions
  }

  type TopicResource = ConfigMap

  def createAction(appNamespace: String,
                   labels: CloudflowLabels)(topic: TopicInfo)(implicit ctx: DeploymentContext): CreateOrUpdateAction[ConfigMap] = {
    val (bootstrapServers, brokerConfig) = topic.bootstrapServers match {
      case Some(bootstrapServers) => bootstrapServers                  -> topic.brokerConfig
      case None                   => ctx.kafkaContext.bootstrapServers -> ctx.kafkaContext.properties
    }
    val partitions        = topic.partitions.getOrElse(ctx.kafkaContext.partitionsPerTopic)
    val replicationFactor = topic.replicationFactor.getOrElse(ctx.kafkaContext.replicationFactor)
    val configMap         = resource(appNamespace, topic, partitions, replicationFactor, labels)

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
            val newTopic = new NewTopic(topic.name, partitions, replicationFactor.toShort).configs(topic.properties.asJava)
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

  def resource(namespace: String, topic: TopicInfo, partitions: Int, replicationFactor: Int, labels: CloudflowLabels)(
      implicit ctx: DeploymentContext
  ): ConfigMap =
    ConfigMap(
      metadata = ObjectMeta(name = s"topic-${topic.id}", labels = labels(topic.id), namespace = namespace),
      data = Map(
          "id"                -> topic.id,
          "name"              -> topic.name,
          "partitions"        -> partitions.toString,
          "replicationFactor" -> replicationFactor.toString
        ) ++ topic.properties
    )

  private val editor = new ObjectEditor[ConfigMap] {
    override def updateMetadata(obj: ConfigMap, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }

  object TopicInfo {
    def apply(t: Topic): TopicInfo = TopicInfo(
      t.id,
      t.name,
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
