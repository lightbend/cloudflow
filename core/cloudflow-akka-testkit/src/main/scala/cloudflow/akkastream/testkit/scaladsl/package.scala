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

package cloudflow.akkastream.testkit

import cloudflow.streamlets.ConfigParameter

package object scaladsl {
  type InletTap[T] = cloudflow.akkastream.testkit.InletTap[T]
  type OutletTap[T] = cloudflow.akkastream.testkit.OutletTap[T]
}

package scaladsl {
  case object Completed extends cloudflow.akkastream.testkit.Completed
  final case class ConfigParameterValueImpl private (
      configParameterKey: String, value: String
  ) extends ConfigParameterValue

  object ConfigParameterValue {
    def apply(configParameter: ConfigParameter, value: String): ConfigParameterValue = {
      ConfigParameterValueImpl(configParameter.key, value)
    }
  }
}
