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

package cloudflow.operator
package event

import io.fabric8.kubernetes.api.model.{ HasMetadata, ObjectReference }
import io.fabric8.kubernetes.client.informers.EventType

object Event extends Event {
  def toObjectReference(hm: HasMetadata): ObjectReference = {
    new ObjectReference(
      hm.getApiVersion,
      "",
      hm.getKind,
      hm.getMetadata.getName,
      hm.getMetadata.getNamespace,
      hm.getMetadata.getResourceVersion,
      hm.getMetadata.getUid)
  }
}
trait Event {

  def changeInfo[T <: HasMetadata](watchEvent: WatchEvent[T]) = {
    val metadata = watchEvent.obj.getMetadata
    s"(${getKind(watchEvent.obj)} ${metadata.getName} ${watchEvent.eventType})"
  }
  def getKind(obj: HasMetadata) =
    if (Option(obj.getKind).isEmpty) obj.getClass.getSimpleName else obj.getKind // sometimes kind is empty.
}

case class WatchEvent[T <: HasMetadata](obj: T, eventType: EventType)
