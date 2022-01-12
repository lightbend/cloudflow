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

import cloudflow.operator.action.runner._
import io.fabric8.kubernetes.api.model.{ Quantity, SecretBuilder }

import scala.jdk.CollectionConverters._

trait TestDeploymentContext {
  implicit val ctx: DeploymentContext =
    DeploymentContext(
      akkaRunnerDefaults = AkkaRunnerDefaults(
        resourceConstraints = ResourceConstraints(
          cpuRequests = Quantity.parse("100m"),
          memoryRequests = Quantity.parse("128m"),
          cpuLimits = Some(Quantity.parse("1")),
          memoryLimits = Some(Quantity.parse("512m"))),
        javaOptions = "-Xmx1024"),
      podName = "cloudflow-operator",
      podNamespace = "cloudflow")
  val runners = Map(AkkaRunner.Runtime -> new AkkaRunner(ctx.akkaRunnerDefaults))

  def getSecret(content: String) = {
    new SecretBuilder()
      .withData(Map(cloudflow.operator.event.ConfigInput.PodsConfigDataKey ->
      Base64Helper.encode(content)).asJava)
      .build()
  }

  def getSecret(content: String, runtime: String) = {
    new SecretBuilder()
      .withData(Map(
        cloudflow.operator.event.ConfigInput.PodsConfigDataKey ->
        Base64Helper.encode(content),
        cloudflow.operator.event.ConfigInput.RuntimeConfigDataKey ->
        Base64Helper.encode(runtime)).asJava)
      .build()
  }
}
