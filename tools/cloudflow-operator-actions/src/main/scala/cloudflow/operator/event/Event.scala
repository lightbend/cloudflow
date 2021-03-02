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

import io.fabric8.kubernetes.api.model.{ HasMetadata, WatchEvent }

object Event extends Event
trait Event {

  def changeInfo[T <: HasMetadata](watchEvent: WatchEvent) = {
    def getObject(we: WatchEvent) = we.getObject.asInstanceOf[T]

    val obj = getObject(watchEvent)
    val metadata = obj.getMetadata
    s"(${getKind(obj)} ${metadata.getName} ${watchEvent.getType})"
  }
  def getKind(obj: HasMetadata) =
    if (Option(obj.getKind).isEmpty) obj.getClass.getSimpleName else obj.getKind // sometimes kind is empty.
}
