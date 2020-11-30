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
import scala.concurrent.duration._
import scala.collection.immutable
import akka.actor.ActorSystem
import akka.pattern._
import org.slf4j.LoggerFactory
import play.api.libs.json._
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client._
import io.fabric8.kubernetes.client.dsl.{ AnyNamespaceable, Namespaceable, Resource }
import io.fabric8.kubernetes.client.utils.Serialization

import scala.reflect.ClassTag
import scala.util.Try

/**
 * Captures an action to create, delete or update a Kubernetes resource.
 */
sealed trait Action {

  /**
   * Executes the action using a KubernetesClient.
   * Returns the action that was executed
   */
  def execute(client: KubernetesClient)(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action]

  /**
   * The action name.
   */
  def name: String

  /*
   * The namespace that the action must take place in, or none for using the namespace of the client.
   */
  def namespace: Option[String]
  /*
   * Message to log before the action executes
   */
  def executing: String
  /*
   * Message to log after the action has executed
   */
  def executed: String
}

/**
 * Creates actions.
 */
object Action {
  val log  = LoggerFactory.getLogger(Action.getClass)
  def noop = NoopAction

  /**
   * Creates a [[CreateOrUpdateAction]].
   */
  def createOrUpdate[T <: HasMetadata](resource: T)(implicit ct: ClassTag[T]) =
    new CreateOrUpdateAction(resource)

  /**
   * Creates a [[DeleteAction]].
   */
  def delete[T <: HasMetadata](resourceName: String, namespace: String)(implicit ct: ClassTag[T]): DeleteAction[T] =
    DeleteAction(resourceName, namespace)

  // TODO use a predicate: predicateForUpdate
  def createOrPatch[T <: HasMetadata](
      resource: T,
      patch: T
  )(implicit ct: ClassTag[T]) =
    new CreateOrPatchAction(resource, patch)

  /**
   * Creates an [[PatchAction]].
   */
  // TODO use a predicate: predicateForUpdate
  def patch[T <: HasMetadata](
      resource: T,
      patch: T
  )(implicit ct: ClassTag[T]) = new PatchAction(resource, patch)

  /**
   * Creates a [[CompositeAction]]. A single action that encapsulates other actions.
   */
  def composite[T <: HasMetadata](actions: immutable.Iterable[Action]): CompositeAction[T] =
    CompositeAction(actions)

  /**
   * Creates an action provided that a resource with resourceName in namespace is found.
   */
  def provided[T <: HasMetadata](resourceName: String, namespace: String)(
      fAction: Option[T] => Action
  )(implicit ct: ClassTag[T]): ProvidedAction[T] =
    new ProvidedAction(resourceName, namespace, fAction)

  def providedRetry[T <: HasMetadata](resourceName: String, namespace: String, getRetries: Int)(
      fAction: Option[T] => Action
  )(implicit ct: ClassTag[T]): ProvidedAction[T] =
    new ProvidedAction(resourceName, namespace, fAction)

  def providedRetry[T <: HasMetadata](resourceName: String, namespace: String)(
      fAction: Option[T] => Action
  )(implicit ct: ClassTag[T]): ProvidedAction[T] = providedRetry(resourceName, namespace, getRetries = 60)(fAction)

  /**
   * Creates an action provided that a list of resources with a label in a namespace are found.
   */
//  def providedByLabel[T <: HasMetadata](labelKey: String, labelValues: Vector[String], namespace: String)(
//      fAction: ListResource[T] => Action
//  )(
//      implicit format: Format[T],
//      resourceDefinition: ResourceDefinition[ListResource[T]]
//  ): ProvidedByLabelAction[T] =
//    new ProvidedByLabelAction(labelKey, labelValues, namespace, fAction, format, resourceDefinition)

  /**
   * Creates an [[UpdateStatusAction]].
   */
  def updateStatus[T <: HasMetadata](
      resource: T,
      predicateForUpdate: ((Option[T], T) => Boolean) = (_: Option[T], _: T) => true
  )(implicit ct: ClassTag[T]) = new UpdateStatusAction(resource, predicateForUpdate)

  /**
   * Log message for when an [[Action]] is about to get executed.
   */
  def executing(action: Action) = action.executing

  /**
   * Log message for when an [[Action]] has been executed.
   */
  def executed(action: Action) = action.executed
}

case object NoopAction extends ResourceAction[Nothing] {
  def execute(client: KubernetesClient)(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] =
    Future.successful(this)
  def name: String      = "noop"
  def namespace         = None
  def executing: String = "<noop>"
  def executed: String  = "<noop>"
}

object ResourceAction {
  val log          = LoggerFactory.getLogger("ResourceAction")
  val ConflictCode = 409
}

abstract class ResourceAction[+T] extends Action {
  import ResourceAction._

  /**
   * It is expected that f will always first get the resource in question to break out of the conflict, to avoid a fast recover loop.
   */
  protected def recoverFromError[O](future: Future[O], client: KubernetesClient, retries: Int, f: (KubernetesClient, Int) => Future[O])(
      implicit ec: ExecutionContext
  ): Future[O] = {
    def recover(e: Throwable) =
      if (retries > 0) {
        log.info(s"Recovering from error: ${e.getMessage}")
        f(client, retries)
      } else {
        log.error(s"Exhausted retries recovering from error, giving up.")
        throw e
      }

    future.recoverWith {
      case e: KubernetesClientException if (e.getCode == ConflictCode) => recover(e)
      case e: akka.stream.StreamTcpException                           => recover(e)
      case e: akka.stream.scaladsl.TcpIdleTimeoutException             => recover(e)
    }
  }
}

abstract class SingleResourceAction[T <: HasMetadata] extends ResourceAction[T] {

  /**
   * The resource that is applied
   */
  def resource: T

  /**
   * The name of the resource that this action is applied to
   */
  def resourceName = resource.getMetadata.getName
  def namespace    = Some(resource.getMetadata.getNamespace)

  def executing =
    s"Executing $name action for ${resource.getKind}/${resource.getMetadata.getNamespace}/${resource.getMetadata.getName}"
  def executed =
    s"Executed $name action for ${resource.getKind}/${resource.getMetadata.getNamespace}/${resource.getMetadata.getName}"
}

/**
 * Captures create or update of the resource. This action does not fail if the resource already exists.
 * If the resource already exists, it will be updated.
 */
class CreateOrUpdateAction[T <: HasMetadata](
    val resource: T
) extends SingleResourceAction[T] {

  val name = "create-or-update"

  /**
   * Creates the resources if it does not exist. If it does exist it updates the resource as required.
   */
  def execute(client: KubernetesClient)(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] =
    for {
      result <- executeCreate(client)
    } yield new CreateOrUpdateAction(resource)

  private def executeCreate(client: KubernetesClient, retries: Int = 60)(implicit sys: ActorSystem, ec: ExecutionContext): Future[T] = {
    val nextRetries = retries - 1

    val existing = Try {
      client
        .resource(resource.getMetadata.getName)
        .inNamespace(namespace.getOrElse(client.getNamespace))
        .get()
    }.toOption

    for {
      res <- existing
        .map { existingResource =>
          val prevMetadata = existingResource.getMetadata
          val newMetadata  = resource.getMetadata

          newMetadata.setResourceVersion(prevMetadata.getResourceVersion)
          recoverFromError(
            Future { client.resource(resource).createOrReplace() },
            client,
            nextRetries,
            executeCreate
          )
        }
        .getOrElse(
          recoverFromError(Future { client.resource(resource).createOrReplace() }, client, nextRetries, executeCreate)
        )
    } yield res
  }
}

/**
 * Captures the update of the resource.
 */
class CreateOrPatchAction[T <: HasMetadata](
    val resource: T,
    val patch: T
)(implicit ct: ClassTag[T])
    extends SingleResourceAction[T] {

  val name = "create-or-patch"

  /**
   * Updates the resource, without changing the `resourceVersion`.
   */
  def execute(client: KubernetesClient)(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] =
    for {
      result <- executeCreateOrPatch(client)
    } yield new CreateOrPatchAction(result, patch)

  private def executeCreateOrPatch(
      client: KubernetesClient,
      retries: Int = 60
  )(implicit sys: ActorSystem, ec: ExecutionContext): Future[T] = {
    val nextRetries = retries - 1

    val existing = Try {
      client
        .resource(resource)
        .inNamespace(namespace.getOrElse(client.getNamespace))
        .get()
    }.toOption
    for {
      res <- existing
        .map(_ =>
          recoverFromError(
            Future {
              client
                .resource(resource)
                .inNamespace(resource.getMetadata.getNamespace)
                .get()
                .asInstanceOf[Resource[T, Doneable]] // TODO: this is not going to work ...
                .patch(patch)                        // TODO: does this ... works?
            },
            client,
            nextRetries,
            executeCreateOrPatch
          )
        )
        .getOrElse(recoverFromError(Future { client.resource(resource).createOrReplace() }, client, nextRetries, executeCreateOrPatch))
    } yield res
  }
}

class PatchAction[T <: HasMetadata](
    val resource: T,
    val patch: T
)(implicit ct: ClassTag[T])
    extends SingleResourceAction[T] {

  val name = "patch"

  /**
   * Updates the target resource using a patch
   */
  def execute(client: KubernetesClient)(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] =
    Future {
      val r = client
        .resource(resource)
        .get()
        .asInstanceOf[Resource[T, Doneable]] // TODO: this is not going to work ...
        .patch(patch)

      new PatchAction(r, patch)
    }
}

/**
 * Captures the status subresource update of the resource.
 * The `resource` needs to have the subresource set (for instance using `withStatus`)
 */
class UpdateStatusAction[T <: HasMetadata](
    val resource: T,
    predicateForUpdate: ((Option[T], T) => Boolean) = (_: Option[T], _: T) => true
)(implicit ct: ClassTag[T])
    extends SingleResourceAction[T] {

  val name = "updateStatus"

  /**
   * Updates the resource status subresource, without changing the `resourceVersion`.
   */
  def execute(client: KubernetesClient)(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] =
    for {
      result <- executeUpdateStatus(client)
    } yield new UpdateStatusAction(result)

  def executeUpdateStatus(client: KubernetesClient, retries: Int = 60)(implicit sys: ActorSystem, ec: ExecutionContext): Future[T] = {
    val existing = Try {
      client
        .resource(resource)
        .inNamespace(namespace.getOrElse(client.getNamespace))
    }.toOption

    //    TODO: port the update ..
    //    for {
    //      resourceVersionUpdated = existing
    //        .map(existingResource =>
    //          editor.updateMetadata(resource, resource.metadata.copy(resourceVersion = existingResource.metadata.resourceVersion))
    //        )
    //      res <- resourceVersionUpdated
    //        .map { resourceToUpdate =>
    //          if (predicateForUpdate(existing, resourceToUpdate)) {
    //            recoverFromError(client.resource(resource).updateStatus(resourceToUpdate), client, fabric8Client, retries - 1, executeUpdateStatus)
    //          } else {
    //            Action.log.info(s"Ignoring status update for resource ${resource.metadata.name}")
    //            Future.successful(resource)
    //          }
    //        }
    //        .getOrElse(Future.successful(resource))
    //    } yield res
    ???
  }
}

/**
 * Captures deletion of the resource.
 */
final case class DeleteAction[T <: HasMetadata](val resourceName: String, _namespace: String)(
    implicit ct: ClassTag[T]
) extends ResourceAction[T] {
  import io.fabric8.kubernetes.api.model.{ DeletionPropagation, ObjectMetaBuilder }
  import io.fabric8.kubernetes.client.utils.Serialization

  val name      = "delete"
  val namespace = Some(_namespace)
  override def executing =
    s"Deleting $resourceName resource"
  override def executed =
    s"Deleted $resourceName resource"

  private val log = LoggerFactory.getLogger(this.getClass.getName)

  private val meta = {
    val default = {
      Serialization
        .jsonMapper()
        .readValue("{}", ct.runtimeClass)
        .asInstanceOf[T]
    }

    val metadata = new ObjectMetaBuilder()
      .withName(resourceName)
      .withNamespace(_namespace)
      .build()

    default.setMetadata(metadata)

    default
  }

  /*
   * Deletes the resource.
   */
  def execute(client: KubernetesClient)(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] =
    Future {
      log.debug(s"Going to delete resource $meta, name: ${meta.getMetadata.getName}, namespace: ${meta.getMetadata.getNamespace}")

      client
        .resource[T](meta)
        .inNamespace(_namespace)
        .withPropagationPolicy(DeletionPropagation.FOREGROUND)
        .delete()

      this
    }
}

final case class CompositeAction[T <: HasMetadata](
    actions: immutable.Iterable[Action]
) extends ResourceAction[T] {
  require(actions.nonEmpty)
  val name      = "composite"
  val namespace = actions.head.namespace
  override def executing =
    s"""Composite action executing in namespace $namespace:\n ${actions.map(_.name).mkString("\n")}"""
  override def executed =
    s"""Composite action executed in namespace $namespace:\n ${actions.map(_.name).mkString("\n")}"""

  /**
   * Executes all actions
   */
  override def execute(
      client: KubernetesClient
  )(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] =
    Future.sequence(actions.map(_.execute(client))).map(_ => this)
}

final class ProvidedAction[T <: HasMetadata](
    val resourceName: String,
    val _namespace: String,
    val getAction: Option[T] => Action,
    getRetries: Int = 0
)(implicit ct: ClassTag[T])
    extends ResourceAction[T] {
  val name      = "provide"
  val namespace = Some(_namespace)
  override def executing =
    s"Providing $namespace/$resourceName to next action"
  override def executed =
    s"Provided $namespace/$resourceName to next action"

  def execute(client: KubernetesClient)(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] =
    executeWithRetry(
      client,
      delay = 1.second,
      retries = 60,
      retriesGet = getRetries
    )

  private val meta = {
    val default = {
      Serialization
        .jsonMapper()
        .readValue("{}", ct.runtimeClass)
        .asInstanceOf[T]
    }

    val metadata = new ObjectMetaBuilder()
      .withName(resourceName)
      .withNamespace(_namespace)
      .build()

    default.setMetadata(metadata)

    default
  }

  private def executeWithRetry(
      client: KubernetesClient,
      delay: FiniteDuration,
      retries: Int,
      retriesGet: Int
  )(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] = {
    def getAndProvide(retriesGet: Int): Future[Action] =
      Try {
        client
          .resource[T](meta)
          .inNamespace(namespace.getOrElse(client.getNamespace))
          .get()
      }.toOption.map { maybe =>
        maybe match {
          case Some(_) => getAction(maybe).execute(client)
          case None if retriesGet > 0 =>
            Action.log.info(
              s"Scheduling retry to get resource $namespace/$resourceName, retries left: ${retriesGet - 1}"
            )
            after(delay, sys.scheduler)(getAndProvide(retriesGet - 1))
          case None =>
            Action.log.info(s"Did not find resource $namespace/$resourceName")
            getAction(maybe).execute(client)
        }
      }

    getAndProvide(retriesGet).recoverWith {
      case e: KubernetesClientException if retries > 0 =>
        Action.log.info(
          s"Scheduling retry to get resource $namespace/$resourceName, cause: ${e.getClass.getSimpleName} message: ${e.getMessage}"
        )
        after(delay, sys.scheduler)(executeWithRetry(client, delay, retries - 1, retriesGet))
    }
  }
}

final class ProvidedByLabelAction[T <: HasMetadata](
    val labelKey: String,
    val labelValues: Vector[String],
    val _namespace: String
    // val getAction: ListResource[T] => Action,
) extends ResourceAction[T] {
  val name         = "provideByLabel"
  val namespace    = Some(_namespace)
  val resourceName = "n/a"

  def executing =
    s"Providing a list of resources in $namespace to next action"
  def executed =
    s"Provided a list of resources in $namespace to next action"

  def execute(client: KubernetesClient)(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] =
    executeWithRetry(
      client,
      delay = 1.second,
      retries = 60
    )

  private def executeWithRetry(
      client: KubernetesClient,
      delay: FiniteDuration,
      retries: Int
  )(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] =
    //    TODO: Implement me
    //    val selector: LabelSelector = LabelSelector(LabelSelector.InRequirement(labelKey, labelValues.toList))
    // def getAndProvide: Future[Action]
    //      client
    //        .usingNamespace(namespace.getOrElse(client.namespaceName))
    //        .listSelected[ListResource[T]](selector)(skuber.json.format.ListResourceFormat[T], resourceDefinition, lc)
    //        .flatMap { list =>
    //          getAction(list).execute(client, fabric8Client)
    //        }
    //
    //    getAndProvide.recoverWith {
    //      case t: Throwable if retries > 0 =>
    //        sys.log.info(s"Scheduling retry to getting resources by label spec in $namespace, reason: ${t.getClass.getSimpleName}")
    //        after(delay, sys.scheduler)(executeWithRetry(client, fabric8Client, delay, retries - 1))
    //    }
    //  }
    ???
}

final case class AppAction(action: Action, app: CloudflowApplication.CR) extends Action {
  def execute(client: KubernetesClient)(implicit sys: ActorSystem, ec: ExecutionContext): Future[Action] = action.execute(client)
  def executed: String                                                                                   = action.executed
  def executing: String                                                                                  = action.executing
  def name: String                                                                                       = action.name
  def namespace: Option[String]                                                                          = action.namespace
}
