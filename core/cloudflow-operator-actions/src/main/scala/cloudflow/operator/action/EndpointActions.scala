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

package cloudflow.operator
package action

import scala.concurrent._
import scala.collection.immutable._
import akka.actor.ActorSystem
import play.api.libs.json._

import skuber._
import skuber.api.client._
import skuber.json.format._

import cloudflow.blueprint.deployment._

/**
 * Creates a sequence of resource actions for the endpoint changes
 * between a current application and a new application.
 */
object EndpointActions {
  def apply(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR]
  ): Seq[Action] = {
    def distinctEndpoints(app: CloudflowApplication.Spec) =
      app.deployments.flatMap(deployment => deployment.endpoint).toSet

    val currentEndpoints = currentApp.map(cr => distinctEndpoints(cr.spec)).getOrElse(Set.empty[Endpoint])
    val newEndpoints     = distinctEndpoints(newApp.spec)

    val deleteActions = (currentEndpoints -- newEndpoints).flatMap { endpoint =>
      Seq(
        Action.delete[Service](Name.ofService(StreamletDeployment.name(newApp.spec.appId, endpoint.streamlet)), newApp.namespace)
      )
    }.toList
    val createActions = (newEndpoints -- currentEndpoints).flatMap { endpoint =>
      Seq(
        createServiceAction(endpoint, newApp, StreamletDeployment.name(newApp.spec.appId, endpoint.streamlet))
      )
    }.toList
    deleteActions ++ createActions
  }

  private def serviceResource(endpoint: Endpoint,
                              streamletDeploymentName: String,
                              namespace: String,
                              labels: CloudflowLabels,
                              ownerReferences: List[OwnerReference]): Service = {
    val servicePort =
      Service.Port(
        name = Name.ofContainerPort(endpoint.containerPort),
        port = endpoint.containerPort,
        targetPort = Some(Name.ofContainerPort(endpoint.containerPort))
      )

    Service(
      metadata = ObjectMeta(
        name = Name.ofService(streamletDeploymentName),
        namespace = namespace,
        labels = labels(Name.ofService(streamletDeploymentName)),
        ownerReferences = ownerReferences
      ),
      spec = Some(Service.Spec(ports = List(servicePort)))
    ).withSelector(CloudflowLabels.Name -> Name.ofPod(streamletDeploymentName))
  }

  private def createServiceAction(endpoint: Endpoint,
                                  app: CloudflowApplication.CR,
                                  streamletDeploymentName: String): CreateServiceAction = {
    val labels = CloudflowLabels(app)
    val ownerReferences = List(
      OwnerReference(app.apiVersion, app.kind, app.metadata.name, app.metadata.uid, Some(true), Some(true))
    )

    CreateServiceAction(serviceResource(endpoint, streamletDeploymentName, app.namespace, labels, ownerReferences))
  }

  /**
   * Creates an action for creating a service.
   */
  object CreateServiceAction {
    def apply(service: Service)(implicit format: Format[Service], resourceDefinition: ResourceDefinition[Service]) =
      new CreateServiceAction(service, format, resourceDefinition)
  }

  private val serviceEditor: ObjectEditor[Service] = (obj: Service, newMetadata: ObjectMeta) => obj.copy(metadata = newMetadata)

  /**
   * Creates an action for creating a service.
   * If the service already exists, it will be updated. A service has an immutable clusterIP field which is retained in the update.
   */
  class CreateServiceAction(
      override val resource: Service,
      format: Format[Service],
      resourceDefinition: ResourceDefinition[Service]
  ) extends CreateOrUpdateAction[Service](resource, format, resourceDefinition, serviceEditor) {
    override def execute(
        client: KubernetesClient
    )(implicit sys: ActorSystem, ec: ExecutionContext, lc: LoggingContext): Future[ResourceAction[Service]] =
      for {
        serviceResult <- client.getOption[Service](resource.name)(format, resourceDefinition, lc)
        res <- serviceResult
          .map { existingService =>
            val resourceVersionUpdated = resource
              .withResourceVersion(existingService.metadata.resourceVersion)
              .withClusterIP(existingService.spec.map(_.clusterIP).getOrElse(""))
            client.update(resourceVersionUpdated)(format, resourceDefinition, lc).map(o => CreateServiceAction(o))
          }
          .getOrElse(client.create(resource)(format, resourceDefinition, lc).map(o => CreateServiceAction(o)))
      } yield res
  }
}
