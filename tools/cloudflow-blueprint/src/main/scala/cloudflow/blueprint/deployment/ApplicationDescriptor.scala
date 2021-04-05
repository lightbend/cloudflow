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

package cloudflow.blueprint.deployment

import akka.datap.crd.App

import com.typesafe.config._
import cloudflow.blueprint._
import io.fabric8.kubernetes.client.utils.Serialization

/**
 * A full description of all information required to deploy and operate
 * a Cloudflow application.
 */
object ApplicationDescriptor {
  /*
   * The version of the Application Descriptor Format.
   * This version is also hardcoded in (versions of) kubectl-cloudflow in `domain.SupportedApplicationDescriptorVersion`.
   */
  val Version = "5"

  val PrometheusAgentKey = "prometheus"

  def apply(
      appId: String,
      appVersion: String,
      image: String,
      blueprint: VerifiedBlueprint,
      agentPaths: Map[String, String],
      libraryVersion: String): App.Spec = {

    val sanitizedApplicationId = Dns1123Formatter.transformToDNS1123Label(appId)
    val namedStreamletDescriptors = blueprint.streamlets.map(streamletToNamedStreamletDescriptor)
    val deployments =
      namedStreamletDescriptors
        .map {
          case (streamlet, instance) =>
            StreamletDeployment(sanitizedApplicationId, instance, image, portMappingsForStreamlet(streamlet, blueprint))
        }
    val streamlets = namedStreamletDescriptors.map {
      case (_, instance) =>
        App.Streamlet(instance.name, sanitizeDescriptor(instance.descriptor))
    }

    App.Spec(
      appId = sanitizedApplicationId,
      appVersion = appVersion,
      streamlets = streamlets,
      deployments = deployments,
      agentPaths = agentPaths,
      version = Option(Version),
      libraryVersion = Option(libraryVersion))
  }

  def portMappingsForStreamlet(
      streamlet: VerifiedStreamlet,
      blueprint: VerifiedBlueprint): Map[String, App.PortMapping] =
    blueprint.topics.flatMap { topic =>
      topic.connections.filter(_.streamlet.name == streamlet.name).map { verifiedPort =>
        val config = Serialization
          .jsonMapper()
          .readTree(
            topic.kafkaConfig
              .root()
              .render(ConfigRenderOptions.concise().setOriginComments(false).setComments(false).setJson(true)))
        verifiedPort.portName -> App.PortMapping(id = topic.id, cluster = topic.cluster, config = config)
      }
    }.toMap
  private def streamletToNamedStreamletDescriptor(streamlet: VerifiedStreamlet) =
    (streamlet, App.Streamlet(streamlet.name, streamlet.descriptor))

  /**
   *  Deletes every schema
   *  StreamletDescriptor.[inlets | outlets].SchemaDescriptor.schema
   *  to avoid adding the description of each type of the schema in the CR
   */
  private def sanitizeDescriptor(descriptor: App.Descriptor): App.Descriptor = {
    val sanitizedInlets = descriptor.inlets.map(each => each.copy(schema = each.schema.copy(schema = "")))
    val sanitizedOutlets = descriptor.outlets.map(each => each.copy(schema = each.schema.copy(schema = "")))
    descriptor.copy(inlets = sanitizedInlets, outlets = sanitizedOutlets)
  }
}

/**
 * Describes the deployable unit for a single streamlet instance, e.g. everything
 * required to deploy it.
 */
object StreamletDeployment {
  val ServerAttributeName = "server"
  val EndpointContainerPort = 3000

  def name(appId: String, streamlet: String) = s"${appId}.${streamlet}"

  def apply(
      appId: String,
      streamlet: App.Streamlet,
      image: String,
      portMappings: Map[String, App.PortMapping],
      containerPort: Int = EndpointContainerPort,
      replicas: Option[Int] = None): App.Deployment = {
    val (config, endpoint) = configAndEndpoint(appId, streamlet, containerPort)
    App.Deployment(
      name = name(appId, streamlet.name),
      runtime = streamlet.descriptor.runtime,
      image = image,
      streamletName = streamlet.name,
      className = streamlet.descriptor.className,
      endpoint = endpoint,
      secretName = Dns1123Formatter.transformToDNS1123SubDomain(streamlet.name),
      config = Serialization
        .jsonMapper()
        .readTree(
          config
            .root()
            .render(ConfigRenderOptions.concise().setComments(false).setOriginComments(false).setJson(true))),
      portMappings = portMappings,
      volumeMounts = streamlet.descriptor.volumeMounts.toList,
      replicas = replicas)
  }

  private def configAndEndpoint(
      appId: String,
      streamlet: App.Streamlet,
      containerPort: Int): Tuple2[Config, Option[App.Endpoint]] =
    streamlet.descriptor
      .getAttribute(ServerAttributeName)
      .map { serverAttribute =>
        (
          ConfigFactory.parseString(s"${serverAttribute.configPath} = ${containerPort}"),
          Some(
            App.Endpoint(
              appId = Option(appId),
              streamlet = Option(streamlet.name),
              containerPort = Option(containerPort))))
      }
      .getOrElse((ConfigFactory.empty(), None))
}
