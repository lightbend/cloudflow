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

import scala.concurrent._

import akka.NotUsed
import akka.stream.scaladsl._
import skuber._
import skuber.api.client._

trait Event {

  def changeInfo[T <: ObjectResource](watchEvent: WatchEvent[T]) = {
    val obj      = watchEvent._object
    val metadata = obj.metadata
    s"(${getKind(obj)} ${metadata.name} ${watchEvent._type})"
  }
  def getKind(obj: ObjectResource) = if (obj.kind.isEmpty) obj.getClass.getSimpleName else obj.kind // sometimes kind is empty.

  /**
   * TODO rewrite using `ProvidedAction`, ensuring all K8s effects are executed in executeActions.
   * Finds the associated [[CloudflowApplication.CR]]s for [[AppChangeEvent]]s.
   * The resulting flow outputs tuples of the app and the streamlet change event.
   */
  def mapToAppInSameNamespace[O <: ObjectResource, E <: AppChangeEvent[_]](
      client: KubernetesClient
  )(implicit ec: ExecutionContext): Flow[E, (Option[CloudflowApplication.CR], E), NotUsed] =
    Flow[E].mapAsync(1) { changeEvent ⇒
      val ns = changeEvent.namespace
      client.usingNamespace(ns).getOption[CloudflowApplication.CR](changeEvent.appId).map { cr =>
        cr -> changeEvent
      }
    }
}
