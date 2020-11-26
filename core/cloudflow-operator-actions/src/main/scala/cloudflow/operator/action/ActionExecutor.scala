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

package cloudflow.operator.action

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.collection.immutable.Seq

/**
 * Executes Kubernetes resource actions.
 * Any non-fatal exception in execute should result in a failure containing an [[ActionException]]
 */
trait ActionExecutor {
  def executionContext: ExecutionContext

  /**
   * Executes the action. Returns the action as executed, containing the object as it was returned by the action.
   * In the case of deletion, the original resource is returned.
   */
  def execute(action: Action): Future[Action]

  /**
   * Executes the actions. Returns the actions as executed, containing the objects as they were returned by the actions.
   * In the case of deletion, the original resource is returned.
   */
  def execute(actions: Seq[Action]): Future[Seq[Action]] = {
    implicit val ec = executionContext
    Future.sequence(actions.map(execute))
  }
}

/**
 * Exception thrown when the action failed to make the appropriate change(s) for the application identified by `appId`.
 */
case class ActionException(action: Action, msg: String, cause: Throwable) extends Exception(msg, cause) {
  def this(action: Action, cause: Throwable) = {
    this(action, s"Action ${action.name} failed: ${cause.getMessage}", cause)
  }
  def this(action: Action, msg: String) = {
    this(action, s"Action ${action.name} failed: ${msg}", null)
  }
}
