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

package cloudflow.streamlets

/**
 * Describes that a streamlet requires specific configuration by the cloudflow platform.
 *
 * The configuration value will be added by the platform under the `configPath` path in the Streamlet configuration.
 */
trait StreamletAttribute {
  /** the prefix used for all attribute driven configuration, to prevent clashes with user defined configuration */
  final val configPrefix = "cloudflow.internal"
  /** The path to the section where configuration information is stored */
  final def configSection: String = s"$configPrefix.$attributeName"
  /** The absolute path to the config value in the the config for the Streamlet */
  final def configPath = s"$configSection.$configKey"

  /** The name of the attribute */
  def attributeName: String
  /** The key in the section pointed to by the `configSection` path, where the config value is stored */
  def configKey: String
}
