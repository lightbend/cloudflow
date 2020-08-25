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
import skuber._

/**
 * Creates sequences of resource [[Action]]s deployment and undeployment of applications.
 * The [[Action]]s record the required changes between an optional current application and a new application.
 * The [[ActionExecutor]] executes these actions.
 */
object Actions {

  /**
   * Creates the [[Action]]s to deploy the application.
   * the deployment actions are derived from changes between the current application and the new application to deploy.
   * A [[CloudflowApplication]] consists of 1-R runners(one for each streamlet), which expose 0-E endpoints.
   * The application data is kept in 0-S savepoints.
   */
  def deploy(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR] = None,
      namespace: String,
      cause: ObjectResource
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    require(currentApp.forall(_.spec.appId == newApp.spec.appId))
    val labels          = CloudflowLabels(newApp)
    val ownerReferences = CloudflowApplication.getOwnerReferences(newApp)
    prepareNamespace(newApp.spec.appId, namespace, labels, ownerReferences) ++
      deployTopics(newApp, currentApp) ++
      deployRunners(newApp, currentApp, namespace) ++
      // If an existing status is there, update status based on app (expected pod counts)
      // in case pod events do not occur, for instance when a operator delegated to is not responding
      newApp.status.flatMap { st =>
        val newStatus = st.updateApp(newApp)
        if (newStatus != st) Some(newStatus.toAction(newApp))
        else None
      }.toList ++
      EventActions.deployEvents(newApp, currentApp, namespace, cause)
  }

  /**
   * Creates the [[Action]]s to undeploy the application.
   * The undeploy is derived by reverting the [[CreateOrUpdateAction]]s that defined the
   * creation of the application.
   */
  def undeploy(
      app: CloudflowApplication.CR,
      namespace: String,
      cause: ObjectResource
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    val actions = deployRunners(app, currentApp = None, namespace)
    actions.collect {
      case createAction: CreateOrUpdateAction[_] ⇒ createAction.revert
    } :+ EventActions.undeployEvent(app, namespace, cause)
  }

  def prepareNamespace(
      appId: String,
      namespace: String,
      labels: CloudflowLabels,
      ownerReferences: List[OwnerReference]
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] =
    AppActions(appId, namespace, labels, ownerReferences)

  private def deployTopics(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR]
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] =
    TopicActions(newApp)

  private def deployRunners(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR],
      namespace: String
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] =
    EndpointActions(newApp, currentApp, namespace) ++
        AkkaRunnerActions(newApp, currentApp, namespace) ++
        SparkRunnerActions(newApp, currentApp, namespace) ++
        FlinkRunnerActions(newApp, currentApp, namespace)
}
