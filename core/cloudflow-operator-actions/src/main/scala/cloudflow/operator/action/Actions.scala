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
import cloudflow.operator.action.runner.Runner

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
      runners: Map[String, runner.Runner[_]],
      namespace: String,
      podName: String,
      podNamespace: String,
      cause: ObjectResource
  ): Seq[Action] = {
    require(currentApp.forall(_.spec.appId == newApp.spec.appId))
    val labels          = CloudflowLabels(newApp)
    val ownerReferences = CloudflowApplication.getOwnerReferences(newApp)
    prepareNamespace(newApp, namespace, runners, labels, ownerReferences) ++
      deployTopics(newApp, podNamespace) ++
      deployRunners(newApp, currentApp, namespace, runners) ++
      // If an existing status is there, update status based on app (expected pod counts)
      // in case pod events do not occur, for instance when a operator delegated to is not responding
      newApp.status.flatMap { st =>
        val newStatus = st.updateApp(newApp)
        if (newStatus != st) Some(newStatus.toAction(newApp))
        else None
      }.toList ++
      EventActions.deployEvents(newApp, currentApp, namespace, runners, podName, cause)
  }

  /**
   * Creates the [[Action]]s to undeploy the application.
   */
  def undeploy(
      app: CloudflowApplication.CR,
      namespace: String,
      podName: String,
      cause: ObjectResource
  ): Seq[Action] =
    Seq(EventActions.undeployEvent(app, namespace, podName, cause))

  def prepareNamespace(
      app: CloudflowApplication.CR,
      namespace: String,
      runners: Map[String, runner.Runner[_]],
      labels: CloudflowLabels,
      ownerReferences: List[OwnerReference]
  ): Seq[Action] =
    PrepareNamespaceActions(app, namespace, runners, labels, ownerReferences)

  private def deployTopics(
      newApp: CloudflowApplication.CR,
      podNamespace: String
  ): Seq[Action] =
    TopicActions(newApp, podNamespace)

  private def deployRunners(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR],
      namespace: String,
      runners: Map[String, Runner[_]]
  ): Seq[Action] =
    EndpointActions(newApp, currentApp, namespace) ++
        runners.map { case (_, runner) => runner.actions(newApp, currentApp, namespace) }.flatten
}
