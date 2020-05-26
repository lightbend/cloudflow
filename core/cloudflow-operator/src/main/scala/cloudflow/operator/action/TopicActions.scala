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

import scala.collection.immutable._

import play.api.libs.json.Format
import play.api.libs.json.Json

import skuber._
import skuber.ResourceSpecification.Subresources

import cloudflow.blueprint.deployment._

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
      app.deployments.flatMap(_.portMappings.values.map(topic => TopicInfo(topic.name, topic.managed))).toSet

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

  type TopicResource = CustomResource[Spec, Status]
  private implicit val ConditionFmt: Format[Condition] = Json.format[Condition]
  private implicit val SpecFmt: Format[Spec]           = Json.format[Spec]
  private implicit val StatusFmt: Format[Status]       = Json.format[Status]

  private implicit val Definition = ResourceDefinition[CustomResource[Spec, Status]](
    group = "kafka.strimzi.io",
    version = "v1beta1",
    kind = "KafkaTopic",
    subresources = Some(Subresources().withStatusSubresource)
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[TopicResource]

  def deleteAction(labels: CloudflowLabels)(topic: TopicInfo)(implicit ctx: DeploymentContext) =
    Action.delete(resource(topic, labels))

  def createAction(labels: CloudflowLabels)(topic: TopicInfo)(implicit ctx: DeploymentContext) =
    Action.createOrUpdate(resource(topic, labels), editor)

  def resource(topic: TopicInfo, labels: CloudflowLabels)(
      implicit ctx: DeploymentContext
  ): CustomResource[Spec, Status] = {
    val partitions  = ctx.kafkaContext.partitionsPerTopic
    val replicas    = ctx.kafkaContext.replicationFactor
    val clusterName = ctx.kafkaContext.strimziClusterName

    // TODO when strimzi supports it, create in the namespace where the CloudflowApplication resides.
    val ns = ctx.kafkaContext.strimziTopicOperatorNamespace

    CustomResource[Spec, Status](Spec(partitions, replicas))
      .withMetadata(
        ObjectMeta(
          name = topic.name,
          namespace = ns,
          labels = labels(topic.name) + ("strimzi.io/cluster" -> Name.ofLabelValue(clusterName))
        )
      )
  }

  private val editor = new ObjectEditor[CustomResource[Spec, Status]] {
    override def updateMetadata(obj: CustomResource[Spec, Status], newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }
  object TopicInfo {
    def apply(sp: Topic): TopicInfo = TopicInfo(sp.name, sp.managed)
  }
  case class TopicInfo(name: String, managed: Boolean)
}
