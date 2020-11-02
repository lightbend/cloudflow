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

import org.slf4j.LoggerFactory
import skuber.{ ObjectResource, Secret }
import skuber.json.format._

import cloudflow.operator.action.runner._

/**
 * Creates a sequence of resource actions for the runner changes
 * between a current application and a new application.
 */
abstract class RunnerActions[T <: ObjectResource](runner: Runner[T]) {
  private val log = LoggerFactory.getLogger(this.getClass)

  protected def actions(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR],
      namespace: String
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    implicit val format             = runner.format
    implicit val resourceDefinition = runner.resourceDefinition

    val newDeployments = newApp.spec.deployments.filter(_.runtime == runner.runtime)

    val currentDeployments     = currentApp.map(_.spec.deployments.filter(_.runtime == runner.runtime)).getOrElse(Vector())
    val currentDeploymentNames = currentDeployments.map(_.name)
    val newDeploymentNames     = newDeployments.map(_.name)

    // delete streamlet deployments by name that are in the current app but are not listed in the new app
    val deleteActions = currentDeployments
      .filterNot(deployment ⇒ newDeploymentNames.contains(deployment.name))
      .flatMap { deployment ⇒
        Seq(
          Action.delete[T](runner.resourceName(deployment), namespace),
          Action.delete[T](runner.configResourceName(deployment), namespace)
        )
      }

    // create streamlet deployments by name that are not in the current app but are listed in the new app
    val createActions = newDeployments
      .filterNot(deployment ⇒ currentDeploymentNames.contains(deployment.name))
      .flatMap { deployment ⇒
        Seq(
          Action.createOrUpdate(runner.configResource(deployment, newApp, namespace), runner.configEditor),
          Action.provided[Secret, ObjectResource](
            deployment.secretName,
            namespace, {
              case Some(secret) => Action.createOrUpdate(runner.resource(deployment, newApp, secret, namespace), runner.editor)
              case None =>
                val msg = s"Secret ${deployment.secretName} is missing for streamlet deployment '${deployment.name}'."
                log.error(msg)
                CloudflowApplication.Status.errorAction(newApp, msg)
            }
          )
        )
      }

    // update streamlet deployments by name that are in both the current app and the new app
    val updateActions = newDeployments
      .filter(deployment ⇒ currentDeploymentNames.contains(deployment.name))
      .flatMap { deployment ⇒
        if (runner == SparkRunner) {
          val patchAction = Action.provided[Secret, ObjectResource](
            deployment.secretName,
            namespace, {
              case Some(secret) =>
                val resource = SparkRunner.resource(deployment, newApp, secret, namespace)
                val patch    = SparkRunner.patch(deployment, newApp, secret, namespace)
                Action.patch(resource, patch)(SparkRunner.format, SparkRunner.patchFormat, SparkRunner.resourceDefinition)
              case None =>
                val msg = s"Secret ${deployment.secretName} is missing for streamlet deployment '${deployment.name}'."
                log.error(msg)
                CloudflowApplication.Status.errorAction(newApp, msg)
            }
          )
          val configAction = Action.createOrUpdate(runner.configResource(deployment, newApp, namespace), runner.configEditor)
          Seq(configAction, patchAction)
        } else {
          Seq(
            Action.createOrUpdate(runner.configResource(deployment, newApp, namespace), runner.configEditor),
            Action.provided[Secret, ObjectResource](
              deployment.secretName,
              namespace, {
                case Some(secret) =>
                  Action.createOrUpdate(runner.resource(deployment, newApp, secret, namespace), runner.editor)
                case None =>
                  val msg = s"Secret ${deployment.secretName} is missing for streamlet deployment '${deployment.name}'."
                  log.error(msg)
                  CloudflowApplication.Status.errorAction(newApp, msg)
              }
            )
          )
        }
      }

    deleteActions ++ createActions ++ updateActions
  }
}
