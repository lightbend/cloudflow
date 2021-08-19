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

/**
 * Provides defaults for deployment.
 */
case class DeploymentContext(
    akkaRunnerDefaults: AkkaRunnerDefaults,
    sparkRunnerDefaults: SparkRunnerDefaults,
    flinkRunnerDefaults: FlinkRunnerDefaults,
    podName: String,
    podNamespace: String) {
  def infoMessage = s"""
   | pod-name:                         ${podName}
   | pod-namespace                     ${podNamespace}
  """
}
