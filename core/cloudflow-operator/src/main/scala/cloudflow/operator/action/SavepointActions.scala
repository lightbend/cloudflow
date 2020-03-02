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
object SavepointActions {
  def apply(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR],
      deleteOutdatedTopics: Boolean
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    def distinctSavepoints(app: CloudflowApplication.Spec): Set[Savepoint] =
      app.deployments.flatMap(_.portMappings.values).toSet

    val labels = CloudflowLabels(newApp)
    val ownerReferences = List(
      OwnerReference(newApp.apiVersion, newApp.kind, newApp.metadata.name, newApp.metadata.uid, Some(true), Some(true))
    )

    val currentSavepoints = currentApp.map(cr => distinctSavepoints(cr.spec)).getOrElse(Set.empty[Savepoint])
    val newSavepoints     = distinctSavepoints(newApp.spec)

    val deleteActions =
      if (deleteOutdatedTopics) {
        (currentSavepoints -- newSavepoints).toVector
          .map(deleteAction(labels, ownerReferences))
      } else {
        Vector.empty[Action[ObjectResource]]
      }

    val createActions =
      (newSavepoints -- currentSavepoints).toVector
        .map(createAction(labels, ownerReferences))
    deleteActions ++ createActions
  }

  final case class Condition(`type`: String, status: String, lastTransitionTime: String, reason: String, message: String)

  final case class Spec(partitions: Int, replicas: Int)

  final case class Status(conditions: List[Condition], observedGeneration: Int)

  type Topic = CustomResource[Spec, Status]
  private implicit val ConditionFmt: Format[Condition] = Json.format[Condition]
  private implicit val SpecFmt: Format[Spec]           = Json.format[Spec]
  private implicit val StatusFmt: Format[Status]       = Json.format[Status]

  private implicit val Definition = ResourceDefinition[CustomResource[Spec, Status]](
    group = "kafka.strimzi.io",
    version = "v1beta1",
    kind = "KafkaTopic",
    subresources = Some(Subresources().withStatusSubresource)
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[Topic]

  def deleteAction(labels: CloudflowLabels, ownerReferences: List[OwnerReference])(savepoint: Savepoint)(implicit ctx: DeploymentContext) =
    Action.delete(resource(savepoint, labels, ownerReferences))

  def createAction(labels: CloudflowLabels, ownerReferences: List[OwnerReference])(savepoint: Savepoint)(implicit ctx: DeploymentContext) =
    Action.create(resource(savepoint, labels, ownerReferences), editor)

  def resource(savepoint: Savepoint, labels: CloudflowLabels, ownerReferences: List[OwnerReference])(
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
          name = savepoint.name,
          namespace = ns,
          labels = labels(savepoint.name) + ("strimzi.io/cluster" -> clusterName),
          ownerReferences = ownerReferences
        )
      )
  }

  private val editor = new ObjectEditor[CustomResource[Spec, Status]] {
    override def updateMetadata(obj: CustomResource[Spec, Status], newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }
}
