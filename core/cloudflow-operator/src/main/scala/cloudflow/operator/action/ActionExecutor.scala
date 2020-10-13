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

import scala.concurrent.Future

import skuber._

/**
 * Executes Kubernetes resource actions.
 * Any non-fatal exception in execute should result in a failure containing an [[ActionException]]
 */
trait ActionExecutor {

  /**
   * Executes the action. Returns the action as executed, containing the object as it was returned by the action.
   * In the case of deletion, the original resource is returned.
   */
  def execute(action: Action[ObjectResource]): Future[Action[ObjectResource]]
}

/**
 * Exception thrown when the action failed to make the appropriate change(s) for the application identified by `appId`.
 */
case class ActionException(action: Action[ObjectResource], cause: Throwable)
    extends Exception(s"Action ${action.name} failed: ${cause.getMessage}", cause)
