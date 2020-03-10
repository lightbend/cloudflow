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

package cloudflow.streamlets

import java.nio.file.{ Path, Paths }

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

trait StreamletContext {

  /**
   * A [[cloudflow.streamlets.StreamletDefinition StreamletDefinition]] that informs this context about the
   * parametrization of this particular streamlet instance
   * @return
   */
  private[streamlets] def streamletDefinition: StreamletDefinition

  /**
   * The streamlet reference which identifies the streamlet in the blueprint. It is used in a [[Streamlet]] for logging and metrics,
   * referring back to the streamlet instance using a name recognizable by the user.
   */
  def streamletRef: String = streamletDefinition.streamletRef

  /**
   * Get the savepoint path (topic name) from the port
   *
   * @param port the StreamletPort
   * @return the savepoint path
   * @throws PortNotFoundException if there is no mapping found
   */
  def findSavepointPathForPort(port: StreamletPort): SavepointPath =
    streamletDefinition
      .resolveSavepoint(port)
      .getOrElse(throw PortNotFoundException(port, streamletDefinition))

  /**
   * The full configuration for the [[Streamlet]], containing all
   * deployment-time configuration parameters on top of the normal
   * configuration
   */
  def config: Config = streamletDefinition.config

  /**
   * The subset of configuration specific to a single named instance of a streamlet.
   *
   * A [[Streamlet]] can specify the set of environment-
   * and instance-specific configuration keys it will use during runtime
   * through [[cloudflow.streamlets.Streamlet.configParameters configParameters]]. Those keys will
   * then be made available through this configuration.
   *
   * An empty configuration will be returned if the streamlet doesn't contain any configuration parameters.
   */
  final def streamletConfig: Config = {
    val path = s"cloudflow.streamlets.${streamletRef}"
    if (config.hasPath(path))
      config.getConfig(path)
    else
      ConfigFactory.empty()
  }

  /**
   * The path mounted for a VolumeMount request from a streamlet.
   * In a clustered deployment, the mounted path will correspond to the requested mount path in the
   * [[cloudflow.streamlets.VolumeMount VolumeMount]] definition.
   * In a local environment, this path will be replaced by a local folder.
   * @param volumeMount the volumeMount declaration for which we want to obtain the mounted path.
   * @return the path where the volume is mounted.
   * @throws MountedPathUnavailableException in the case the path is not available.
   */
  def getMountedPath(volumeMount: VolumeMount): Path =
    streamletDefinition.volumeMounts
      .find(vm ⇒ vm.name == volumeMount.name)
      .map(mount ⇒ Paths.get(mount.path))
      .getOrElse(throw MountedPathUnavailableException(volumeMount))

  case class MountedPathUnavailableException(volumeMount: VolumeMount)
      extends Exception(s"Mount path for Volume Mount named [${volumeMount.name}] is unavailable.")

}

case class PortNotFoundException(port: StreamletPort, streamletDefinition: StreamletDefinition)
    extends Exception(
      s"Streamlet port ${port.name} not found for ${streamletDefinition.appId} and streamlet ${streamletDefinition.streamletRef}"
    )
