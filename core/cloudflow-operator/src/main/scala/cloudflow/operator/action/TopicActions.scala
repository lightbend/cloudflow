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

import scala.collection.immutable._
import play.api.libs.json.Format
import play.api.libs.json.Json
import skuber._
import cloudflow.blueprint.deployment._
import com.typesafe.config.Config
import org.apache.kafka.clients.admin.{ Admin, AdminClientConfig, CreateTopicsOptions, NewTopic }
import org.apache.kafka.common.KafkaFuture
import skuber.api.client.KubernetesClient

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future, Promise }

/**
 * Creates a sequence of resource actions for the savepoint changes
 * between a current application and a new application.
 */
object TopicActions {
  def apply(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR],
      deleteOutdatedTopics: Boolean
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    def distinctTopics(app: CloudflowApplication.Spec): Set[TopicInfo] =
      app.deployments.flatMap(_.portMappings.values.map(topic => TopicInfo(topic))).toSet

    val labels = CloudflowLabels(newApp)

    val currentTopics = currentApp.map(cr => distinctTopics(cr.spec)).getOrElse(Set.empty[TopicInfo])
    val newTopics     = distinctTopics(newApp.spec)

    val deleteActions =
      if (deleteOutdatedTopics) {
        (currentTopics -- newTopics).toVector
          .flatMap(topic => if (topic.managed) Some(deleteAction(labels)(topic)) else None)
      } else {
        Vector.empty[Action[ObjectResource]]
      }

    val createActions =
      (newTopics -- currentTopics).toVector
        .flatMap(topic => if (topic.managed) Some(createAction(labels)(topic)) else None)
    deleteActions ++ createActions
  }

  final case class Condition(`type`: Option[String],
                             status: Option[String],
                             lastTransitionTime: Option[String],
                             reason: Option[String],
                             message: Option[String])

  final case class Spec(partitions: Int, replicas: Int)

  final case class Status(conditions: Option[List[Condition]], observedGeneration: Option[Int])

  type TopicResource = ConfigMap
  private implicit val OwnerReferenceFmt: Format[OwnerReference] = Json.format[OwnerReference]
  private implicit val ObjectMetaFmt: Format[ObjectMeta]         = Json.format[ObjectMeta]
  private implicit val ConfigMapFmt: Format[ConfigMap]           = Json.format[ConfigMap]

  def deleteAction(labels: CloudflowLabels)(topic: TopicInfo)(implicit ctx: DeploymentContext) =
//    val ns = ctx.kafkaContext.strimziTopicOperatorNamespace
//    Action.delete[TopicResource](topic.name, ns)
    ???

  def createAction(labels: CloudflowLabels)(topic: TopicInfo)(implicit ctx: DeploymentContext) = {
    val bootstrapServers  = topic.bootstrapServers.getOrElse(ctx.kafkaContext.bootstrapServers)
    val partitions        = topic.partitions.getOrElse(ctx.kafkaContext.partitionsPerTopic)
    val replicationFactor = topic.replicationFactor.getOrElse(ctx.kafkaContext.replicationFactor)
    val configMap         = resource(topic, partitions, replicationFactor, labels)

    val adminClient = KafkaAdmins.getOrCreate(bootstrapServers)

    new CreateOrUpdateAction[ConfigMap](configMap, ConfigMapFmt, implicitly[ResourceDefinition[ConfigMap]], editor) {
      override def execute(
          client: KubernetesClient
      )(implicit sys: ActorSystem, ec: ExecutionContext, lc: skuber.api.client.LoggingContext): Future[Action[ConfigMap]] =
        super.execute(client).flatMap { resourceCreatedAction =>
          val options = new CreateTopicsOptions()
          val result =
            adminClient.createTopics(Collections.singleton(new NewTopic(topic.name, partitions, replicationFactor.toShort)), options)
          result.all().asScala.map(_ => resourceCreatedAction)
        }
    }
  }

  def resource(topic: TopicInfo, partitions: Int, replicationFactor: Int, labels: CloudflowLabels)(
      implicit ctx: DeploymentContext
  ): ConfigMap = {
    val metadata = ObjectMeta(name = s"topic-${topic.name}", labels = labels(topic.name))
    ConfigMap(
      metadata = metadata,
      data = Map(
          "name"              -> topic.name,
          "partitions"        -> partitions.toString,
          "replicationFactor" -> replicationFactor.toString
        ) ++ topic.configMap
    )
  }

  private val editor = new ObjectEditor[ConfigMap] {
    override def updateMetadata(obj: ConfigMap, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }

  object TopicInfo {
    def apply(sp: Topic): TopicInfo = TopicInfo(
      sp.name,
      intOrEmpty(sp.config, Blueprint.PartitionsKey),
      intOrEmpty(sp.config, Blueprint.ReplicasKey),
      Topic.pathAsMap(sp.config, Blueprint.TopicConfigKey),
      sp.managed,
      stringOrEmpty(sp.config, Blueprint.BootstrapServersKey)
    )

    private def intOrEmpty(config: Config, key: String): Option[Int] =
      if (config.hasPath(key)) Some(config.getInt(key)) else None
    private def stringOrEmpty(config: Config, key: String): Option[String] =
      if (config.hasPath(key)) Some(config.getString(key)) else None
  }

  case class TopicInfo(name: String,
                       partitions: Option[Int],
                       replicationFactor: Option[Int],
                       configMap: Map[String, String],
                       managed: Boolean,
                       bootstrapServers: Option[String])

  object KafkaAdmins {
    private var admins = Map.empty[String, Admin]

    def getOrCreate(bootstrapServers: String): Admin =
      if (admins.contains(bootstrapServers)) admins(bootstrapServers)
      else {
        val conf = new java.util.HashMap[String, AnyRef]()
        conf.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        val a = Admin.create(conf)
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
