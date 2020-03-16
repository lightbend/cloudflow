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

case class CloudflowLabels(partOf: String, appVersion: String) {

  import CloudflowLabels._

  val baseLabels: Map[String, String] = Map(
    PartOf    -> partOf,
    ManagedBy -> CloudflowLabels.ManagedByCloudflow,
    Version   -> appVersion
  )

  def apply(name: String): Map[String, String] = baseLabels + (Name -> name)

  def withComponent(name: String, component: String): Map[String, String] =
    this(name) + (CloudflowLabels.Component -> component)
}

object CloudflowLabels {

  def apply(app: CloudflowApplication.CR): CloudflowLabels =
    CloudflowLabels(app.spec.appId, app.spec.appVersion)

  // The name of the application
  val Name = "app.kubernetes.io/name"
  // The component within the architecture
  val Component = "app.kubernetes.io/component"
  // The name of a higher level application this one is part of
  val PartOf = "app.kubernetes.io/part-of"
  // The tool being used to manage the operation of an application
  val ManagedBy = "app.kubernetes.io/managed-by"
  // The git tag of the user application
  val Version = "app.kubernetes.io/version"

  // Streamlet component type
  val StreamletComponent = "streamlet"

  // Managed by
  val ManagedByCloudflow = "cloudflow"
}
