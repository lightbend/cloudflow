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

import cloudflow.operator.runner._

object AkkaRunnerActions extends RunnerActions(AkkaRunner) {
  def apply(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR],
      namespace: String
  )(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = actions(newApp, currentApp, namespace)
}
