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

import scala.concurrent._
import scala.util.control.NonFatal

import akka.actor.ActorSystem

import skuber._
import skuber.api.Configuration

/**
 * Executes Kubernetes resource actions using skuber KubernetesClients.
 */
final class SkuberActionExecutor(
    k8sConfig: Configuration = Configuration.defaultK8sConfig
)(implicit system: ActorSystem, executionContext: ExecutionContext)
    extends ActionExecutor {
  implicit val lc = skuber.api.client.RequestLoggingContext()
  def execute(action: Action[ObjectResource]): Future[Action[ObjectResource]] = {
    // An appropriate KubernetesClient is built up for the object resource namespace
    val namespace = action.namespace
    system.log.debug(Action.executing(action))
    val kubernetesClient =
      k8sInit(k8sConfig.setCurrentNamespace(namespace))
    action
      .execute(kubernetesClient)
      .map { executedAction ⇒
        kubernetesClient.close
        executedAction
      }
      .recoverWith {
        case NonFatal(e) =>
          Future.failed(new ActionException(action, e))
      }
  }
}
