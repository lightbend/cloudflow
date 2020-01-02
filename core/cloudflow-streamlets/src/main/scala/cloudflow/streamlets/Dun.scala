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

import java.io.Serializable

/**
 * Typically used together with `Future` to signal completion
 * but there is no actual value completed. More clearly signals intent
 * than `Unit` and is available both from Scala and Java (which `Unit` is not).
 *
 * Adopted from akka to avoid `streamlets` depending on akka.
 */
sealed abstract class Dun extends Serializable

case object Dun extends Dun {

  /**
   * Java API: the singleton instance
   */
  def getInstance(): Dun = this

  /**
   * Java API: the singleton instance
   *
   * This is equivalent to [[Dun#getInstance()]], but can be used with static import.
   */
  def dun(): Dun = this
}
