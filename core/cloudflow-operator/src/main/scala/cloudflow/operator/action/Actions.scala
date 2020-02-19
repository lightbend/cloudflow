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
      cause: ObjectResource,
      // TODO CSP-1108
      deleteOutdatedTopics: Boolean = false
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    require(currentApp.forall(_.spec.appId == newApp.spec.appId))
    val labels = CloudflowLabels(newApp)
    val ownerReferences = CloudflowOwnerReferences(newApp)
    prepareNamespace(newApp.spec.appId, namespace, labels, ownerReferences) ++
      deploySavepoints(newApp, currentApp, deleteOutdatedTopics) ++
      deployRunners(newApp, currentApp, namespace) ++
      EventActions.deployEvents(newApp, currentApp, namespace, cause)
  }

  /**
   * Creates the [[Action]]s to undeploy the application.
   * The undeploy is derived by reverting the [[CreateAction]]s that defined the
   * creation of the application.
   */
  def undeploy(
      app: CloudflowApplication.CR,
      namespace: String,
      cause: ObjectResource,
      // TODO CSP-1108
      deleteExistingTopics: Boolean = false
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    val currentApp = None

    val savepointActions = if (deleteExistingTopics) {
      deploySavepoints(app, currentApp)
    } else Seq()

    val actions = savepointActions ++ deployRunners(app, currentApp, namespace)

    actions.collect {
      case createAction: CreateAction[_] ⇒ createAction.revert
    } :+ EventActions.undeployEvent(app, namespace, cause)
  }

  def prepareNamespace(
      appId: String,
      namespace: String,
      labels: CloudflowLabels,
      ownerReferences: CloudflowOwnerReferences
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] =
    AppActions(appId, namespace, labels, ownerReferences)

  private def deploySavepoints(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR],
      deleteOutdatedTopics: Boolean = false
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] =
    SavepointActions(newApp, currentApp, deleteOutdatedTopics)

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
