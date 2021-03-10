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

package cloudflow.operator.action

import akka.datap.crd.App
import cloudflow.blueprint._
import cloudflow.blueprint.deployment._
import com.fasterxml.jackson.databind.JsonNode
import com.typesafe.config.{ Config, ConfigRenderOptions }
import io.fabric8.kubernetes.client.utils.Serialization

object CloudflowApplicationSpecBuilder {

  /**
   * Creates a App.Spec from a [[VerifiedBlueprint]].
   */
  def create(
      appId: String,
      appVersion: String,
      image: String,
      blueprint: VerifiedBlueprint,
      agentPaths: Map[String, String]): App.Spec = {

    val sanitizedApplicationId = Dns1123Formatter.transformToDNS1123Label(appId)
    val streamlets = blueprint.streamlets.map(toStreamlet)
    val deployments = {
      ApplicationDescriptor(appId, appVersion, image, blueprint, agentPaths, BuildInfo.version).deployments
        .map(toDeployment)
    }

    App.Spec(
      appId = sanitizedApplicationId,
      appVersion = appVersion,
      deployments = deployments,
      streamlets = streamlets,
      agentPaths = agentPaths,
      version = Some(BuildInfo.version),
      libraryVersion = Some(BuildInfo.version))
  }

  private def toInOutletSchema(schema: SchemaDescriptor) = {
    App.InOutletSchema(
      fingerprint = schema.fingerprint,
      schema = schema.schema,
      name = schema.name,
      format = schema.format)
  }

  private def toInOutlet(in: InletDescriptor) = {
    App.InOutlet(name = in.name, schema = toInOutletSchema(in.schema))
  }
  private def toInOutlet(in: OutletDescriptor) = {
    App.InOutlet(name = in.name, schema = toInOutletSchema(in.schema))
  }

  private def toVolumeMount(vmd: VolumeMountDescriptor) = {
    App.VolumeMountDescriptor(appId = vmd.name, path = vmd.path, accessMode = vmd.accessMode, pvcName = {
      if (vmd.pvcName == null || vmd.pvcName.isEmpty) {
        Some(vmd.pvcName)
      } else None
    })
  }

  private def toConfigParam(cp: ConfigParameterDescriptor) = {
    App.ConfigParameterDescriptor(
      key = cp.key,
      description = cp.description,
      validationType = cp.validationType,
      validationPattern = cp.validationPattern.getOrElse(""),
      defaultValue = cp.defaultValue.getOrElse(""))
  }

  private def toAttribute(a: StreamletAttributeDescriptor) = {
    App.Attribute(attributeName = a.attributeName, configPath = a.configPath)
  }

  private def toRuntime(runtime: StreamletRuntimeDescriptor) = {
    runtime.name
  }

  private def toDescriptor(sd: StreamletDescriptor) = {
    App.Descriptor(
      className = sd.className,
      description = sd.description,
      runtime = toRuntime(sd.runtime),
      labels = sd.labels,
      inlets = sd.inlets.map(toInOutlet),
      outlets = sd.outlets.map(toInOutlet),
      volumeMounts = sd.volumeMounts.map(toVolumeMount),
      configParameters = sd.configParameters.map(toConfigParam),
      attributes = sd.attributes.map(toAttribute))
  }

  private def toStreamlet(streamlet: VerifiedStreamlet) = {
    val descriptor = toDescriptor(streamlet.descriptor)
    App.Streamlet(name = streamlet.name, descriptor = descriptor)
  }

  private def configToJson(config: Config): JsonNode = {
    Serialization
      .jsonMapper()
      .readTree(config.root().render(ConfigRenderOptions.concise().setJson(true)))
  }

  private def toPortMapping(t: cloudflow.blueprint.deployment.Topic) = {
    App.PortMapping(id = t.id, config = configToJson(t.config), cluster = t.cluster)
  }

  private def toEndpoint(endpoint: Endpoint) = {
    App.Endpoint(
      appId = Some(endpoint.appId),
      streamlet = Some(endpoint.streamlet),
      containerPort = Some(endpoint.containerPort))
  }

  private def toDeployment(deployment: StreamletDeployment) = {
    App.Deployment(
      className = deployment.className,
      config = configToJson(deployment.config),
      image = deployment.image,
      name = deployment.name,
      portMappings = deployment.portMappings.map { case (k, v) => k -> toPortMapping(v) },
      volumeMounts = deployment.volumeMounts.getOrElse(List()).map(toVolumeMount),
      runtime = deployment.runtime,
      streamletName = deployment.streamletName,
      secretName = deployment.secretName,
      endpoint = deployment.endpoint.map(toEndpoint),
      replicas = deployment.replicas)
  }
}
