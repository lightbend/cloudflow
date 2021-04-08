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

import scala.util.Try
import com.typesafe.config._
import cloudflow.blueprint._

/**
 * A full description of all information required to deploy and operate
 * a Cloudflow application.
 *
 * Note: this class represents a wire format and therefore tries to minimize
 *       duplication of data
 */
final case class ApplicationDescriptor(appId: String,
                                       appVersion: String,
                                       streamlets: Vector[StreamletInstance],
                                       deployments: Vector[StreamletDeployment],
                                       agentPaths: Map[String, String],
                                       /* The version of the Application Descriptor format */
                                       version: String,
                                       /* The version of the library that has created this Application Descriptor */
                                       libraryVersion: String)

object ApplicationDescriptor {
  /*
   * The version of the Application Descriptor Format.
   * This version is also hardcoded in (versions of) kubectl-cloudflow in `domain.SupportedApplicationDescriptorVersion`.
   */
  val Version = "5"

  val PrometheusAgentKey = "prometheus"

  def apply(appId: String,
            appVersion: String,
            image: String,
            blueprint: VerifiedBlueprint,
            agentPaths: Map[String, String],
            libraryVersion: String): ApplicationDescriptor = {

    val sanitizedApplicationId    = Dns1123Formatter.transformToDNS1123Label(appId)
    val namedStreamletDescriptors = blueprint.streamlets.map(streamletToNamedStreamletDescriptor)
    val deployments =
      namedStreamletDescriptors
        .map {
          case (streamlet, instance) =>
            StreamletDeployment(sanitizedApplicationId, instance, image, portMappingsForStreamlet(streamlet, blueprint))
        }

    ApplicationDescriptor(
      sanitizedApplicationId,
      appVersion,
      namedStreamletDescriptors.map {
        case (_, instance) =>
          StreamletInstance(instance.name, sanitizeDescriptor(instance.descriptor))
      },
      deployments,
      agentPaths,
      Version,
      libraryVersion
    )
  }

  def portMappingsForStreamlet(streamlet: VerifiedStreamlet, blueprint: VerifiedBlueprint): Map[String, Topic] =
    blueprint.topics.flatMap { topic =>
      topic.connections.filter(_.streamlet.name == streamlet.name).map { verifiedPort =>
        verifiedPort.portName -> Topic(topic.id, topic.cluster, topic.kafkaConfig)
      }
    }.toMap
  private def streamletToNamedStreamletDescriptor(streamlet: VerifiedStreamlet) =
    (streamlet, StreamletInstance(streamlet.name, streamlet.descriptor))

  /**
   *  Deletes every schema
   *  StreamletDescriptor.[inlets | outlets].SchemaDescriptor.schema
   *  to avoid adding the description of each type of the schema in the CR
   */
  private def sanitizeDescriptor(descriptor: StreamletDescriptor): StreamletDescriptor = {
    val sanitizedInlets  = descriptor.inlets.map(each => each.copy(schema = each.schema.copy(schema = "")))
    val sanitizedOutlets = descriptor.outlets.map(each => each.copy(schema = each.schema.copy(schema = "")))
    descriptor.copy(inlets = sanitizedInlets, outlets = sanitizedOutlets)
  }
}

/**
 * Describes an instance of the specified streamlet descriptor.
 * This is the Descriptor counterpart of Streamlet, which is the application-level abstraction.
 * The provided `name` is the name given in the application blueprint definition.
 */
final case class StreamletInstance(name: String, descriptor: StreamletDescriptor)

/**
 * Describes the deployable unit for a single streamlet instance, e.g. everything
 * required to deploy it.
 */
final case class StreamletDeployment(name: String,
                                     runtime: String,
                                     image: String,
                                     streamletName: String,
                                     className: String,
                                     endpoint: Option[Endpoint],
                                     secretName: String,
                                     config: Config,
                                     portMappings: Map[String, Topic],
                                     volumeMounts: Option[List[VolumeMountDescriptor]],
                                     replicas: Option[Int])

object StreamletDeployment {
  val ServerAttributeName   = "server"
  val EndpointContainerPort = 3000

  def name(appId: String, streamlet: String) = s"${appId}.${streamlet}"

  def apply(appId: String,
            streamlet: StreamletInstance,
            image: String,
            portMappings: Map[String, Topic],
            containerPort: Int = EndpointContainerPort,
            replicas: Option[Int] = None): StreamletDeployment = {
    val (config, endpoint) = configAndEndpoint(appId, streamlet, containerPort)
    StreamletDeployment(
      name(appId, streamlet.name),
      streamlet.descriptor.runtime.name,
      image,
      streamlet.name,
      streamlet.descriptor.className,
      endpoint,
      secretName = Dns1123Formatter.transformToDNS1123SubDomain(streamlet.name),
      config,
      portMappings,
      preserveEmpty(streamlet.descriptor.volumeMounts.toList),
      replicas
    )
  }

  def preserveEmpty[T](list: List[T]): Option[List[T]] =
    Option(list).filter(_.nonEmpty)

  private def configAndEndpoint(appId: String, streamlet: StreamletInstance, containerPort: Int): Tuple2[Config, Option[Endpoint]] =
    streamlet.descriptor
      .getAttribute(ServerAttributeName)
      .map { serverAttribute =>
        (ConfigFactory.parseString(s"${serverAttribute.configPath} = ${containerPort}"),
         Some(Endpoint(appId, streamlet.name, containerPort)))
      }
      .getOrElse((ConfigFactory.empty(), None))
}

object Topic {
  def pathAsMap(config: Config, section: String): Map[String, String] = {
    import scala.collection.JavaConverters._
    if (config.hasPath(section)) {
      config
        .getConfig(section)
        .entrySet()
        .asScala
        .map(entry => entry.getKey -> entry.getValue.unwrapped().toString)
        .toMap
    } else Map.empty[String, String]
  }
}

final case class Topic(
    id: String,
    cluster: Option[String] = None, // needs to be top level and not part of config so can be easily parsed in app spec in cli
    config: Config = ConfigFactory.empty()
) {
  def name: String     = Try(config.getString(Blueprint.TopicKey)).getOrElse(id)
  def managed: Boolean = Try(config.getBoolean(Blueprint.ManagedKey)).getOrElse(true)
}

final case class Endpoint(appId: String, streamlet: String, containerPort: Int) {
  val subdomain: String = appId.toLowerCase()
  val path: String      = s"/${streamlet}"
}
