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
import cloudflow.blueprint._
import cloudflow.blueprint.deployment._

object CloudflowApplicationSpecBuilder {

  def toCr()

  /**
   * Creates a CloudflowApplication.Spec from a [[VerifiedBlueprint]].
   */
  def create(
      appId: String,
      appVersion: String,
      image: String,
      blueprint: VerifiedBlueprint,
      agentPaths: Map[String, String]): App.Spec = {

    val sanitizedApplicationId = Dns1123Formatter.transformToDNS1123Label(appId)
    val streamlets = blueprint.streamlets.map(toStreamlet)
    val deployments =
      ApplicationDescriptor(appId, appVersion, image, blueprint, agentPaths, BuildInfo.version).deployments

    App.Spec(
      appId = appId,
      appVersion = appVersion,
      deployments = ???,
      streamlets = streamlets,
      agentPaths = agentPaths,
      version = BuildInfo.version,
      libraryVersion = BuildInfo.version)
//    App.Spec(
//                           appId = appId,
//                           appVersion = appVersion,
//                           deployments: immutable.Seq[Deployment],
//                           @JsonProperty("streamlets")
//                           streamlets: immutable.Seq[Streamlet],
//                           @JsonProperty("agent_paths")
//                           agentPaths: Map[String, String],
//                           @JsonProperty("version")
//                           version: Option[String],
//                           @JsonProperty("library_version")
//                           libraryVersion: Option[String])
//    CloudflowApplication.Spec(sanitizedApplicationId, appVersion, streamlets, deployments, agentPaths)
    ???
  }

  private def toDescriptor(sd: StreamletDescriptor) = {
    App.Descriptor
  }

  private def toStreamlet(streamlet: VerifiedStreamlet) = {
    App.Streamlet(name = streamlet.name, descriptor = streamlet.descriptor)
    StreamletInstance(streamlet.name, streamlet.descriptor)
  }
}
