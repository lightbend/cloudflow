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

package cloudflow.operator.action

import scala.concurrent._

import play.api.libs.json._
import skuber._
import skuber.api.client._
import skuber.api.patch.Patch

/**
 * Captures an action to create, delete or update a Kubernetes resource.
 */
sealed trait Action[+T <: ObjectResource] {

  /**
   * The Kubernetes resource to create, update, or delete
   */
  def resource: T

  /**
   * The action name
   */
  def name: String

  /**
   * Executes the action using a KubernetesClient.
   * Returns the created or modified resource, or None if the resource is deleted.
   */
  def execute(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[Action[T]]

  /**
   * It is expected that f will always first get the resource in question to break out of the conflict, to avoid a fast recover loop.
   */
  protected def recoverFromConflict[O](future: Future[O], client: KubernetesClient, f: KubernetesClient => Future[O])(
      implicit ec: ExecutionContext
  ): Future[O] =
    future.recoverWith {
      case e: K8SException if (e.status.code == Some(Action.ConflictCode)) => {
        f(client)
      }
    }

}

/**
 * Creates actions.
 */
object Action {
  val ConflictCode = 409

  /**
   * Creates a [[CreateOrUpdateAction]].
   */
  def createOrUpdate[T <: ObjectResource](resource: T, editor: ObjectEditor[T])(implicit format: Format[T],
                                                                                resourceDefinition: ResourceDefinition[T]) =
    new CreateOrUpdateAction(resource, format, resourceDefinition, editor)

  /**
   * Creates a [[DeleteAction]].
   */
  def delete[T <: ObjectResource](resource: T)(implicit resourceDefinition: ResourceDefinition[T]) =
    new DeleteAction(resource, resourceDefinition)

  def createOrPatch[T <: ObjectResource, O <: Patch](
      resource: T,
      patch: O
  )(implicit format: Format[T], patchWriter: Writes[O], resourceDefinition: ResourceDefinition[T]) =
    new CreateOrPatchAction(resource, patch, format, patchWriter, resourceDefinition)

  /**
   * Creates an [[PatchAction]].
   */
  def patch[T <: ObjectResource, O <: Patch](resource: T, patch: O)(format: Format[T],
                                                                    patchWriter: Writes[O],
                                                                    resourceDefinition: ResourceDefinition[T]) =
    new PatchAction(resource, patch, format, patchWriter, resourceDefinition)

  /**
   * Creates an [[UpdateStatusAction]].
   */
  def updateStatus[T <: ObjectResource](resource: T, editor: ObjectEditor[T])(implicit format: Format[T],
                                                                              resourceDefinition: ResourceDefinition[T],
                                                                              statusEv: HasStatusSubresource[T]) =
    new UpdateStatusAction(resource, format, resourceDefinition, statusEv, editor)

  /**
   * Log message for when an [[Action]] is about to get executed.
   */
  def executing(action: Action[ObjectResource]) =
    s"Executing ${action.name} action for resource ${action.resource.kind}/${action.resource.metadata.name} in namespace ${action.resource.namespace}"

  /**
   * Log message for when an [[Action]] has been executed.
   */
  def executed(action: Action[ObjectResource]) =
    s"Executed ${action.name} action for ${action.resource.kind} ${action.resource.metadata.name} in namespace ${action.resource.namespace}"

}

/**
 * Captures create or update of the resource. This action does not fail if the resource already exists.
 * If the resource already exists, it will be updated.
 */
class CreateOrUpdateAction[T <: ObjectResource](
    val resource: T,
    implicit val format: Format[T],
    implicit val resourceDefinition: ResourceDefinition[T],
    implicit val editor: ObjectEditor[T]
) extends Action[T] {

  val name = "create"

  /**
   * Creates the resources if it does not exist. If it does exist it updates the resource as required.
   */
  def execute(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[Action[T]] =
    for {
      result <- executeCreate(client)
    } yield new CreateOrUpdateAction(result, format, resourceDefinition, editor)

  private def executeCreate(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[T] =
    for {
      existing ← client.getOption[T](resource.name)
      res ← existing
        .map { existingResource ⇒
          val resourceVersionUpdated =
            editor.updateMetadata(resource, resource.metadata.copy(resourceVersion = existingResource.metadata.resourceVersion))
          recoverFromConflict(
            client.update(resourceVersionUpdated),
            client,
            executeCreate
          )
        }
        .getOrElse(recoverFromConflict(client.create(resource), client, executeCreate))
    } yield res

  /**
   * Reverts the action to create the resource.
   */
  def revert: DeleteAction[T] = new DeleteAction(resource, resourceDefinition)
}

/**
 * Captures the update of the resource.
 */
class CreateOrPatchAction[T <: ObjectResource, O <: Patch](
    val resource: T,
    val patch: O,
    implicit val format: Format[T],
    implicit val patchWriter: Writes[O],
    implicit val resourceDefinition: ResourceDefinition[T]
) extends Action[T] {

  val name = "create-or-patch"

  /**
   * Updates the resource, without changing the `resourceVersion`.
   */
  def execute(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[Action[T]] =
    for {
      result <- executeCreateOrPatch(client)
    } yield new CreateOrPatchAction(result, patch, format, patchWriter, resourceDefinition)

  private def executeCreateOrPatch(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[T] =
    for {
      existing ← client.getOption[T](resource.name)
      res ← existing
        .map(_ ⇒ client.patch(resource.name, patch, Some(resource.ns)))
        .getOrElse(recoverFromConflict(client.create(resource), client, executeCreateOrPatch))
    } yield res
}

class PatchAction[T <: ObjectResource, O <: Patch](
    val resource: T,
    val patch: O,
    implicit val format: Format[T],
    implicit val patchWriter: Writes[O],
    implicit val resourceDefinition: ResourceDefinition[T]
) extends Action[T] {

  val name = "patch"

  /**
   * Updates the target resource using a patch
   */
  def execute(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[Action[T]] =
    client
      .patch(resource.name, patch, Some(resource.ns))
      .map(r ⇒ new PatchAction(r, patch, format, patchWriter, resourceDefinition))
}

/**
 * Captures the status subresource update of the resource.
 * The `resource` needs to have the subresource set (for instance using `withStatus`)
 */
class UpdateStatusAction[T <: ObjectResource](
    val resource: T,
    implicit val format: Format[T],
    implicit val resourceDefinition: ResourceDefinition[T],
    implicit val statusEv: HasStatusSubresource[T],
    val editor: ObjectEditor[T]
) extends Action[T] {

  val name = "update"

  /**
   * Updates the resource status subresource, without changing the `resourceVersion`.
   */
  def execute(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[Action[T]] =
    for {
      result <- executeUpdateStatus(client)
    } yield new UpdateStatusAction(result, format, resourceDefinition, statusEv, editor)

  def executeUpdateStatus(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[T] =
    for {
      existing ← client.getOption[T](resource.name)
      resourceVersionUpdated = existing
        .map(existingResource ⇒
          editor.updateMetadata(resource, resource.metadata.copy(resourceVersion = existingResource.metadata.resourceVersion))
        )
        .getOrElse(resource)
      res ← recoverFromConflict(client.updateStatus(resourceVersionUpdated), client, executeUpdateStatus)
    } yield res
}

/**
 * Captures deletion of the resource.
 */
class DeleteAction[T <: ObjectResource](
    val resource: T,
    val resourceDefinition: ResourceDefinition[T]
) extends Action[T] {

  val name = "delete"

  /**
   * Deletes the resource.
   */
  def execute(client: KubernetesClient)(implicit ec: ExecutionContext, lc: LoggingContext): Future[Action[T]] = {
    val options = DeleteOptions(propagationPolicy = Some(DeletePropagation.Foreground))
    client.deleteWithOptions(resource.metadata.name, options)(resourceDefinition, lc).map(_ ⇒ this)
  }
}
