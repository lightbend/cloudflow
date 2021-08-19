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

import akka.datap.crd.App
import akka.kube.actions.Action
import cloudflow.blueprint.deployment.StreamletDeployment
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ MixedOperation, Resource }

import scala.collection.immutable._
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Creates a sequence of resource actions for the endpoint changes
 * between a current application and a new application.
 */
object EndpointActions {

  final val DefaultContainerPort = 3000

  def apply(newApp: App.Cr, currentApp: Option[App.Cr]): Seq[Action] = {
    def distinctEndpoints(app: App.Spec) =
      app.deployments.flatMap(deployment => deployment.endpoint).toSet

    val currentEndpoints: Set[App.Endpoint] =
      currentApp.map(cr => distinctEndpoints(cr.spec)).getOrElse(Set.empty[App.Endpoint])
    val newEndpoints: Set[App.Endpoint] = distinctEndpoints(newApp.spec)

    val deleteActions = (currentEndpoints -- newEndpoints).flatMap { endpoint: App.Endpoint =>
      Seq(
        Action.delete[Service](
          Name.ofService(StreamletDeployment.name(newApp.spec.appId, endpoint.streamlet.getOrElse("no-name"))),
          newApp.namespace))
    }.toList
    val createActions = (newEndpoints -- currentEndpoints).flatMap { endpoint =>
      Seq(
        createServiceAction(
          endpoint,
          newApp,
          StreamletDeployment.name(newApp.spec.appId, endpoint.streamlet.getOrElse("no-name"))))
    }.toList
    deleteActions ++ createActions
  }

  private def serviceResource(
      endpoint: App.Endpoint,
      streamletDeploymentName: String,
      namespace: String,
      labels: CloudflowLabels,
      ownerReferences: List[OwnerReference]): Service = {
    val servicePort = {
      new ServicePortBuilder()
        .withName(Name.ofContainerPort(endpoint.containerPort.getOrElse(DefaultContainerPort)))
        .withPort(Integer.valueOf(endpoint.containerPort.getOrElse(DefaultContainerPort)))
        .withTargetPort(
          new IntOrStringBuilder()
            .withNewStrVal(Name.ofContainerPort(endpoint.containerPort.getOrElse(DefaultContainerPort)))
            .build())
        .build()
    }

    new ServiceBuilder()
      .withNewMetadata()
      .withName(Name.ofService(streamletDeploymentName))
      .withNamespace(namespace)
      .withLabels(labels(Name.ofService(streamletDeploymentName)).asJava)
      .withOwnerReferences(ownerReferences: _*)
      .endMetadata()
      .withSpec(
        new ServiceSpecBuilder()
          .withType("ClusterIP")
          .withPorts(servicePort)
          .withSelector(Map(CloudflowLabels.Name -> Name.ofPod(streamletDeploymentName)).asJava)
          .build())
      .build()
  }

  private def createServiceAction(endpoint: App.Endpoint, app: App.Cr, streamletDeploymentName: String): Action = {
    val labels = CloudflowLabels(app)
    val ownerReferences = List(AppOwnerReference(app.getMetadata().getName(), app.getMetadata().getUid()))

    CreateServiceAction(serviceResource(endpoint, streamletDeploymentName, app.namespace, labels, ownerReferences))
  }

  /**
   * Creates an action for creating a service.
   */
  case class CreateServiceAction(service: Service)(implicit val lineNumber: sourcecode.Line, val file: sourcecode.File)
      extends Action {

    val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"

    val getOperation = { client: KubernetesClient =>
      (client.services().asInstanceOf[MixedOperation[Service, ServiceList, Resource[Service]]])
    }

    val executeOperation = { services: MixedOperation[Service, ServiceList, Resource[Service]] =>
      val thisService =
        services.inNamespace(service.getMetadata.getNamespace).withName(service.getMetadata.getName)
      Option(thisService.fromServer().get()) match {
        case Some(s) =>
          val resourceVersion = s.getMetadata.getResourceVersion
          val clusterIp = Try {
            val res = s.getSpec.getClusterIP
            assert {
              res != null
            }
            res
          }.getOrElse("")
          val serviceMetadata = s.getMetadata
          serviceMetadata.setResourceVersion(resourceVersion)
          val serviceSpec = s.getSpec
          serviceSpec.setClusterIP(clusterIp)

          thisService.patch(
            new ServiceBuilder(service)
              .withMetadata(serviceMetadata)
              .withSpec(serviceSpec)
              .build())
        case _ =>
          thisService.create(service)
      }
      Action.noop
    }

    def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Action] = {
      Future {
        executeOperation(getOperation(client))
      }.flatMap(_.execute(client))
    }

  }

}
