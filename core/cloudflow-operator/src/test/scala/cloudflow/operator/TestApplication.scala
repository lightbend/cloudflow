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

import cloudflow.blueprint._
import cloudflow.blueprint.deployment._

object CloudflowApplicationSpecBuilder {

  /**
   * Creates a CloudflowApplication.Spec from a [[VerifiedBlueprint]].
   */
  def create(appId: String,
             appVersion: String,
             image: String,
             blueprint: VerifiedBlueprint,
             agentPaths: Map[String, String]): CloudflowApplication.Spec = {

    val sanitizedApplicationId = Dns1123Formatter.transformToDNS1123Label(appId)
    val streamlets             = blueprint.streamlets.map(toStreamlet)
    val connections            = blueprint.connections.map(toConnection)
    val deployments =
      streamlets
        .map { streamlet ⇒
          StreamletDeployment(sanitizedApplicationId, streamlet, image, connections)
        }

    CloudflowApplication.Spec(sanitizedApplicationId, appVersion, streamlets, connections, deployments, agentPaths)
  }

  private def toStreamlet(streamlet: VerifiedStreamlet) = StreamletInstance(streamlet.name, streamlet.descriptor)
  private def toConnection(connection: VerifiedStreamletConnection) =
    Connection(
      connection.verifiedOutlet.portName,
      connection.verifiedOutlet.streamlet.name,
      connection.verifiedInlet.portName,
      connection.verifiedInlet.streamlet.name
    )
}
