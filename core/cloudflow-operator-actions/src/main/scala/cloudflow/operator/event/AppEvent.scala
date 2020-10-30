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
package event

import scala.collection.immutable.Seq

import skuber._
import skuber.api.client._
import cloudflow.operator.action._

/**
 * Indicates that a cloudflow application was deployed or undeployed.
 */
sealed trait AppEvent {
  def app: CloudflowApplication.CR
}
case class DeployEvent(
    app: CloudflowApplication.CR,
    currentApp: Option[CloudflowApplication.CR],
    namespace: String,
    cause: ObjectResource
) extends AppEvent {
  override def toString() = s"DeployEvent for application ${app.spec.appId} in namespace $namespace"
}

case class UndeployEvent(
    app: CloudflowApplication.CR,
    namespace: String,
    cause: ObjectResource
) extends AppEvent {
  override def toString() = s"UndeployEvent for application ${app.spec.appId} in namespace $namespace"
}

/**
 * Indicates that something changed in the cloudflow application.
 */
trait AppChangeEvent[T <: ObjectResource] {
  def watchEvent: WatchEvent[T]
  def namespace: String
  def appId: String
}

object AppEvent {
  def toActionList(appEvent: AppEvent)(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] =
    appEvent match {
      case DeployEvent(app, currentApp, namespace, cause) ⇒
        Actions.deploy(app, currentApp, namespace, cause)
      case UndeployEvent(app, namespace, cause) ⇒
        Actions.undeploy(app, namespace, cause)
    }

  def detected(appEvent: AppEvent) = s"Detected $appEvent"
}
