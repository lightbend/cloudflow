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

package cloudflow.akkastream.testkit

import cloudflow.streamlets.ConfigParameter

package object javadsl {
  type InletTap[T]  = cloudflow.akkastream.testkit.InletTap[T]
  type OutletTap[T] = cloudflow.akkastream.testkit.OutletTap[T]
}

package javadsl {
  case object Completed extends cloudflow.akkastream.testkit.Completed {

    /**
     * Java API: the singleton instance
     */
    def getInstance(): cloudflow.akkastream.testkit.Completed = this

    /**
     * Java API: the singleton instance
     *
     * This is equivalent to [[getInstance]], but can be used with static import.
     */
    def completed(): cloudflow.akkastream.testkit.Completed = this
  }

  final case class ConfigParameterValueImpl private (
      configParameterKey: String,
      value: String
  ) extends ConfigParameterValue

  object ConfigParameterValue {
    def create(configParameter: ConfigParameter, value: String): ConfigParameterValue =
      ConfigParameterValueImpl(configParameter.key, value)
  }
}
