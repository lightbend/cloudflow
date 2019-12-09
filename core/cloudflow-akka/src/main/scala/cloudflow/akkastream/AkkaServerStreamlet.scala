/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

import cloudflow.streamlets._

/**
 * An [[AkkaStreamlet]] that can listen on a port.
 * Using this trait instead of the [[AkkaStreamlet]] ensures that the streamlet will get an endpoint in Kubernetes.
 * This trait mixes on the `Server` trait which is required for using a [[ServerAkkaStreamletLogic]].
 * The [[ServerAkkaStreamletLogic]] provides a `containerPort` and a `getContainerPort()` method.
 * It returns the TCP port that is opened on the container. Listen on all interfaces ("0.0.0.0") and use the port
 * returned by `containerPort` to start a TCP server that will be exposed by an endpoint in Kubernetes.
 */
abstract class AkkaServerStreamlet extends AkkaStreamlet with Server

/**
 * Provides `containerPort` and a `getContainerPort()` method.
 * It returns the TCP port that is opened on the container.
 * A [[ServerAkkaStreamletLogic]] requires an implementation of this trait (for instance an [[AkkaServerStreamlet]]) when it is created.
 */
trait Server { this: AkkaStreamlet ⇒
  protected[cloudflow] override def attributes = Set(ServerAttribute) ++ customAttributes

  /**
   * Returns a TCP port on the container that a streamlet can listen on.
   */
  final def containerPort: Int = ServerAttribute.containerPort(this.context.config)

  /**
   * Java API
   * Returns a TCP port on the container that a streamlet can listen on.
   */
  final def getContainerPort(): Int = containerPort
}
