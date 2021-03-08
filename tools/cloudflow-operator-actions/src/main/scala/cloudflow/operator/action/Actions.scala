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

package cloudflow.operator.action

import akka.datap.crd.App
import akka.kube.actions.Action

import scala.collection.immutable._
import cloudflow.operator.action.runner.Runner
import io.fabric8.kubernetes.api.model.{ ObjectReference, OwnerReference }

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
      newApp: App.Cr,
      currentApp: Option[App.Cr] = None,
      runners: Map[String, runner.Runner[_]],
      podName: String,
      podNamespace: String,
      cause: ObjectReference): Seq[Action] = {
    require(currentApp.forall(_.spec.appId == newApp.spec.appId))
    val labels = CloudflowLabels(newApp)
    val ownerReferences = List(CloudflowApplication.getOwnerReference(newApp))
    prepareNamespace(newApp, runners, labels, ownerReferences) ++
    deployTopics(newApp, runners, podNamespace) ++
    deployRunners(newApp, currentApp, runners) ++
    // If an existing status is there, update status based on app (expected pod counts)
    // in case pod events do not occur, for instance when a operator delegated to is not responding
    Option(newApp.status).flatMap { crStatus =>
      val st = CloudflowApplication.Status(newApp.spec, runners)

      val newStatus = st.updateApp(newApp, runners)
      if (newStatus != st) Some(newStatus.toAction(newApp)())
      else None
    }.toList ++
    EventActions.deployEvents(newApp, currentApp, runners, podName, cause)
  }

  /**
   * Creates the [[Action]]s to undeploy the application.
   */
  def undeploy(app: App.Cr, podName: String, cause: ObjectReference): Seq[Action] =
    Seq(EventActions.undeployEvent(app, podName, cause))

  def prepareNamespace(
      app: App.Cr,
      runners: Map[String, runner.Runner[_]],
      labels: CloudflowLabels,
      ownerReferences: List[OwnerReference]): Seq[Action] =
    PrepareNamespaceActions(app, runners, labels, ownerReferences)

  private def deployTopics(newApp: App.Cr, runners: Map[String, runner.Runner[_]], podNamespace: String): Seq[Action] =
    TopicActions(newApp, runners, podNamespace)

  private def deployRunners(newApp: App.Cr, currentApp: Option[App.Cr], runners: Map[String, Runner[_]]): Seq[Action] =
    EndpointActions(newApp, currentApp) ++
    runners.map { case (_, runner) => runner.actions(newApp, currentApp, runners) }.flatten
}
