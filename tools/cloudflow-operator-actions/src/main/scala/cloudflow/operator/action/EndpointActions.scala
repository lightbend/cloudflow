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

import scala.collection.immutable._
import io.fabric8.kubernetes.api.model.{
  IntOrStringBuilder,
  OwnerReference,
  OwnerReferenceBuilder,
  Service,
  ServiceBuilder,
  ServicePortBuilder,
  ServiceSpecBuilder
}

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Creates a sequence of resource actions for the endpoint changes
 * between a current application and a new application.
 */
object EndpointActions {
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
      // TODO: Check if 3000 is a good default
        .withName(Name.ofContainerPort(endpoint.containerPort.getOrElse(3000)))
        .withPort(Integer.valueOf(endpoint.containerPort.getOrElse(3000)))
        .withTargetPort(
          new IntOrStringBuilder()
            .withNewStrVal(Name.ofContainerPort(endpoint.containerPort.getOrElse(3000)))
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
      .withSpec(new ServiceSpecBuilder()
        .withPorts(servicePort)
        .withSelector(Map(CloudflowLabels.Name -> Name.ofPod(streamletDeploymentName)).asJava)
        .build())
      .build()
  }

  private def createServiceAction(endpoint: App.Endpoint, app: App.Cr, streamletDeploymentName: String): Action = {
    val labels = CloudflowLabels(app)
    val ownerReferences = List(
      // TODO: pretty sure this is repeated somewhere
      new OwnerReferenceBuilder()
        .withApiVersion(app.getApiVersion)
        .withKind(app.getKind)
        .withName(app.getMetadata().getName())
        .withUid(app.getMetadata().getUid())
        .withController(true)
        .withBlockOwnerDeletion(true)
        .build())

    CreateServiceAction(serviceResource(endpoint, streamletDeploymentName, app.namespace, labels, ownerReferences))
  }

  /**
   * Creates an action for creating a service.
   */
  object CreateServiceAction {
    def apply(service: Service) = {

      Action.get[Service](service.getMetadata.getName, service.getMetadata.getNamespace) { serviceOpt =>
        val ser =
          serviceOpt match {
            case Some(serviceResult) =>
              val resourceVersion = serviceResult.getMetadata.getResourceVersion
              val clusterIp = Try {
                val res = serviceResult.getSpec.getClusterIP
                assert {
                  res != null
                }
                res
              }.getOrElse("")
              val serviceMetadata = serviceResult.getMetadata
              serviceMetadata.setResourceVersion(resourceVersion)
              val serviceSpec = serviceResult.getSpec
              serviceSpec.setClusterIP(clusterIp)

              new ServiceBuilder(service)
                .withMetadata(serviceMetadata)
                .withSpec(serviceSpec)
                .build()
            case _ => service
          }
        Action.createOrReplace(ser)
      }
    }
  }

}
