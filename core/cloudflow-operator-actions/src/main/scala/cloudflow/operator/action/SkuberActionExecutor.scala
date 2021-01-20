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

import scala.concurrent._
import scala.util.control.NonFatal
import scala.util.Try

import akka.actor.ActorSystem

import org.slf4j.LoggerFactory
import skuber._
import skuber.api.Configuration

object SkuberActionExecutor {
  lazy val logger = LoggerFactory.getLogger("SkuberActionExecutor")
}

/**
 * Executes Kubernetes resource actions using skuber KubernetesClients.
 */
final class SkuberActionExecutor(
    k8sConfig: Configuration = Configuration.defaultK8sConfig
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends ActionExecutor {
  import SkuberActionExecutor._
  val executionContext = ec
  implicit val lc      = skuber.api.client.RequestLoggingContext()
  def execute(action: Action): Future[Action] =
    action match {
      case skAction: ResourceAction[_] =>
        val namespace = skAction.namespace
        logger.debug(Action.executing(skAction))
        val kubernetesClient = namespace
          .map { ns =>
            k8sInit(k8sConfig.setCurrentNamespace(ns))
          }
          .getOrElse(k8sInit(k8sConfig))
        skAction
          .execute(kubernetesClient)
          .map { executedAction =>
            Try(kubernetesClient.close)
            executedAction
          }
          .recoverWith {
            case NonFatal(cause) =>
              Try(kubernetesClient.close)
              Future.failed(new ActionException(skAction, cause))
          }
      case _ =>
        Future.failed(new ActionException(action, s"SkuberActionExecutor cannot execute ${action.name}"))
    }
}
