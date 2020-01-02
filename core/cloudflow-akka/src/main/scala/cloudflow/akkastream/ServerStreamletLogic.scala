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

package cloudflow.akkastream

/**
 * Extends a StreamletLogic. provides access to a containerPort so that it is possible to
 * start a TCP server inside an [[AkkaStreamlet]] which will be exposed through an endpoint in Kubernetes.
 */
abstract class ServerStreamletLogic(server: Server)(implicit context: AkkaStreamletContext) extends AkkaStreamletLogic {
  /**
   * Returns a TCP port on the container that a streamlet can listen on.
   */
  final def containerPort: Int = server.containerPort

  /**
   * Java API
   * Returns a TCP port on the container that a streamlet can listen on.
   */
  final def getContainerPort(): Int = server.containerPort
}
