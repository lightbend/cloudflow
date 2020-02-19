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
package runner

import cloudflow.blueprint.deployment._
import skuber._
import play.api.libs.json._

object Runner {
  val ConfigMapMountPath = "/etc/cloudflow-runner"
  val SecretMountPath    = "/etc/cloudflow-runner-secret"
  val DownwardApiVolume = Volume(
    name = "downward-api-volume",
    source = Volume.DownwardApiVolumeSource(items = List(
      Volume.DownwardApiVolumeFile(
        fieldRef = Volume.ObjectFieldSelector(fieldPath = "metadata.uid"),
        path = "metadata.uid",
        resourceFieldRef = None
      ),
      Volume.DownwardApiVolumeFile(
        fieldRef = Volume.ObjectFieldSelector(fieldPath = "metadata.name"),
        path = "metadata.name",
        resourceFieldRef = None
      ),
      Volume.DownwardApiVolumeFile(
        fieldRef = Volume.ObjectFieldSelector(fieldPath = "metadata.namespace"),
        path = "metadata.namespace",
        resourceFieldRef = None
      )
    )
    )
  )
  val DownwardApiVolumeMount = Volume.Mount(DownwardApiVolume.name, "/mnt/downward-api-volume/")

  val DockerContainerGroupId = 185
}

/**
 * A Runner translates into a Runner Kubernetes resource, and a ConfigMap that configures the runner.
 */
trait Runner[T <: ObjectResource] {
  // The format for the runner resource T
  def format: Format[T]
  // The editor for the runner resource T to modify the metadata for update
  def editor: ObjectEditor[T]
  // The editor for the configmap to modify the metadata for update
  def configEditor: ObjectEditor[ConfigMap]
  // The resource definition for the runner resource T
  def resourceDefinition: ResourceDefinition[T]

  def runtime: String

  final val RuntimeMainClass = "cloudflow.runner.Runner"
  final val RunnerJarName    = "cloudflow-runner.jar"

  /**
   * Creates the configmap for the runner.
   */
  def configResource(
      deployment: StreamletDeployment,
      app: CloudflowApplication.CR,
      namespace: String
  )(implicit ctx: DeploymentContext): ConfigMap = {
    val labels = CloudflowLabels(app)
    val ownerReferences = CloudflowOwnerReferences(app)
    val prometheusConfig = deployment.runtime match {
      case AkkaRunner.runtime  ⇒ PrometheusConfig(ctx.akkaRunnerSettings.prometheusRules)
      case SparkRunner.runtime ⇒ PrometheusConfig(ctx.sparkRunnerSettings.prometheusRules)
      case FlinkRunner.runtime ⇒ PrometheusConfig(ctx.flinkRunnerSettings.prometheusRules)
    }

    val configData = Vector(
      RunnerConfig(app.spec.appId, app.spec.appVersion, deployment, ctx.kafkaContext.bootstrapServers),
      prometheusConfig
    )
    val name = Name.ofConfigMap(deployment.name)
    ConfigMap(
      metadata = ObjectMeta(name = name, namespace = namespace, labels = labels(name), ownerReferences = ownerReferences.list),
      data = configData.map(cd ⇒ cd.filename -> cd.data).toMap
    )
  }

  /**
   * Creates the runner resource.
   */
  def resource(deployment: StreamletDeployment,
               app: CloudflowApplication.CR,
               namespace: String,
               updateLabels: Map[String, String] = Map())(implicit ctx: DeploymentContext): T
}
