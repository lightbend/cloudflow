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

package cloudflow.blueprint

import java.io._
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.util._

import com.typesafe.config._

object Blueprint {
  val StreamletsSectionKey             = "blueprint.streamlets"
  val TopicsSectionKey                 = "blueprint.topics"
  val UnsupportedConnectionsSectionKey = "blueprint.connections"
  val TopicKey                         = "topic.name"
  val ManagedKey                       = "managed"
  val ProducersKey                     = "producers"
  val ConsumersKey                     = "consumers"
  val ClusterKey                       = "cluster"

  // kafka config items
  // must align with cloudflow.streamlets.Topic
  val BootstrapServersKey = "bootstrap.servers"
  val ConnectionConfigKey = "connection-config"
  val ProducerConfigKey   = "producer-config"
  val ConsumerConfigKey   = "consumer-config"
  val PartitionsKey       = "partitions"
  val ReplicasKey         = "replicas"
  val TopicConfigKey      = "topic"

  /**
   * Parses the blueprint from a String.
   * @param blueprintString the blueprint file contents
   * @param streamletDescriptors the streamlet descriptors
   */
  def parseString(blueprintString: String, streamletDescriptors: Vector[StreamletDescriptor]): Blueprint =
    try {
      parseConfig(ConfigFactory.parseString(blueprintString).resolve(), streamletDescriptors)
    } catch {
      case e: ConfigException => Blueprint(globalProblems = Vector(BlueprintFormatError(e.getMessage)))
    }

  /**
   * Parses the blueprint from a String.
   * @param config a Config containing the blueprint contents
   * @param streamletDescriptors the streamlet descriptors
   */
  def parseConfig(config: Config, streamletDescriptors: Vector[StreamletDescriptor]): Blueprint =
    if (!config.hasPath(StreamletsSectionKey)) {
      Blueprint(globalProblems = Vector(MissingStreamletsSection))
    } else {
      try {
        val streamletRefs = getKeys(config, StreamletsSectionKey).map { key =>
          val simpleClassKey = s"$StreamletsSectionKey.${key}"
          val className      = config.getString(simpleClassKey)

          StreamletRef(name = key, className = className)
        }.toVector

        val topics = if (config.hasPath(TopicsSectionKey)) {
          getKeys(config, TopicsSectionKey).map { key =>
            val producersKey = s"$TopicsSectionKey.${key}.$ProducersKey"
            val consumersKey = s"$TopicsSectionKey.${key}.$ConsumersKey"
            val clusterKey   = s"$TopicsSectionKey.${key}.$ClusterKey"
            val configKey    = s"$TopicsSectionKey.${key}"

            val topicId   = key
            val producers = getStringListOrEmpty(config, producersKey)
            val consumers = getStringListOrEmpty(config, consumersKey)
            val cluster   = getStringOrEmpty(config, clusterKey)
            val kafkaConfig = getConfigOrEmpty(config, configKey)
              .withoutPath(ProducersKey)
              .withoutPath(ConsumersKey)
              .withoutPath(ClusterKey)
            // validate at least that producer and consumer sections are objects.
            // TODO It is possible to create a ConsumerConfig and ProducerConfig from properties and check unused,
            // TODO if badly spelled or unknown settings need to be prevented.
            if (kafkaConfig.hasPath(ConnectionConfigKey)) kafkaConfig.getObject(ConnectionConfigKey)
            if (kafkaConfig.hasPath(ProducerConfigKey)) kafkaConfig.getObject(ProducerConfigKey)
            if (kafkaConfig.hasPath(ConsumerConfigKey)) kafkaConfig.getObject(ConsumerConfigKey)
            if (kafkaConfig.hasPath(TopicConfigKey)) {
              val topicConfig = kafkaConfig.getConfig(TopicConfigKey)
              if (topicConfig.hasPath(PartitionsKey)) topicConfig.getInt(PartitionsKey)
              if (topicConfig.hasPath(ReplicasKey)) topicConfig.getInt(ReplicasKey)
            }
            Topic(topicId, producers, consumers, cluster, kafkaConfig)
          }.toVector
        } else Vector.empty[Topic]

        // not supporting previous format
        if (config.hasPath(UnsupportedConnectionsSectionKey)) {
          throw new ConfigException.BadPath(
            UnsupportedConnectionsSectionKey,
            s"Please specify '${TopicsSectionKey}' section instead of previously supported '${UnsupportedConnectionsSectionKey}' section."
          )
        }

        Blueprint(streamletRefs, topics, streamletDescriptors).verify
      } catch {
        case e: ConfigException => Blueprint(globalProblems = Vector(BlueprintFormatError(e.getMessage)))
      }
    }

  private def getKeys(config: Config, key: String): Vector[String] =
    config
      .getConfig(key)
      .root()
      .entrySet()
      .asScala
      .map(_.getKey)
      .toVector

  private def getConfigOrEmpty(config: Config, key: String): Config =
    if (config.hasPath(key)) config.getConfig(key) else ConfigFactory.empty()
  private def getStringListOrEmpty(config: Config, key: String): Vector[String] =
    if (config.hasPath(key)) config.getStringList(key).asScala.toVector else Vector.empty[String]
  private def getStringOrEmpty(config: Config, key: String): Option[String] =
    if (config.hasPath(key)) Option(config.getString(key)) else None
}

final case class Blueprint(streamlets: Vector[StreamletRef] = Vector.empty[StreamletRef],
                           topics: Vector[Topic] = Vector.empty[Topic],
                           streamletDescriptors: Vector[StreamletDescriptor] = Vector.empty,
                           globalProblems: Vector[BlueprintProblem] = Vector.empty[BlueprintProblem]) {
  val problems = globalProblems ++ streamlets.flatMap(_.problems) ++ topics.flatMap(_.problems)

  val isValid = problems.isEmpty

  def verify: Blueprint = {
    val emptyStreamletsProblem           = if (streamlets.isEmpty) Some(EmptyStreamlets) else None
    val emptyStreamletDescriptorsProblem = if (streamletDescriptors.isEmpty) Some(EmptyStreamletDescriptors) else None

    val newStreamlets      = streamlets.map(_.verify(streamletDescriptors))
    val verifiedStreamlets = newStreamlets.flatMap(_.verified)

    // verify that all topics are unique
    val newTopics      = topics.map(_.verify(verifiedStreamlets))
    val verifiedTopics = newTopics.flatMap(_.verified)

    val duplicatesProblem = verifyNoDuplicateStreamletNames(newStreamlets).left.toOption

    val portNameProblems        = verifyPortNames(streamletDescriptors)
    val configParameterProblems = verifyConfigParameters(streamletDescriptors)
    val volumeMountProblems     = verifyVolumeMounts(streamletDescriptors)

    val unconnectedPortProblems = verifyPortsConnected(verifiedStreamlets, verifiedTopics)
    val portsBoundToManyTopics  = verifyPortsBoundToManyTopics(verifiedTopics)
    val globalProblems =
      Vector(emptyStreamletsProblem, emptyStreamletDescriptorsProblem, duplicatesProblem).flatten ++ unconnectedPortProblems ++ portsBoundToManyTopics ++ portNameProblems ++ configParameterProblems ++ volumeMountProblems

    copy(streamlets = newStreamlets, topics = newTopics, globalProblems = globalProblems)
  }

  def verified: Either[Vector[BlueprintProblem], VerifiedBlueprint] =
    for {
      validBlueprint <- verify.validate
    } yield VerifiedBlueprint(validBlueprint.streamlets.flatMap(_.verified), validBlueprint.topics.flatMap(_.verified))

  private def verifyNoDuplicateStreamletNames(
      streamlets: Vector[StreamletRef]
  ): Either[DuplicateStreamletNamesFound, Vector[StreamletRef]] = {
    val dups =
      streamlets
        .groupBy(_.name.trim())
        .flatMap {
          case (_, duplicateStreamlets) if duplicateStreamlets.size > 1 => duplicateStreamlets
          case _                                                        => Vector.empty[StreamletRef]
        }
    if (dups.isEmpty) Right(streamlets)
    else Left(DuplicateStreamletNamesFound(dups.toVector))
  }

  private def verifyPortsConnected(verifiedStreamlets: Vector[VerifiedStreamlet],
                                   verifiedTopics: Vector[VerifiedTopic]): Vector[UnconnectedPorts] = {
    var problems = Vector.empty[UnconnectedPorts]
    val (outlets, inlets) = verifiedStreamlets
      .flatMap { streamlet =>
        def unconnected(portDescriptors: immutable.IndexedSeq[PortDescriptor]) =
          portDescriptors
            .filterNot { port =>
              verifiedTopics.exists(topic =>
                topic.connections.exists(verifiedPort => verifiedPort.streamlet == streamlet && verifiedPort.portName == port.name)
              )
            }
            .map(port => UnconnectedPort(streamlet.name, port))

        val unconnectedInlets  = unconnected(streamlet.descriptor.inlets)
        val unconnectedOutlets = unconnected(streamlet.descriptor.outlets)
        unconnectedInlets ++ unconnectedOutlets
      }
      .partition(_.port.isOutlet)
    if (outlets.nonEmpty) problems = problems :+ UnconnectedOutlets(outlets)
    if (inlets.nonEmpty) problems = problems :+ UnconnectedInlets(inlets)
    problems
  }
  private def verifyPortsBoundToManyTopics(verifiedTopics: Vector[VerifiedTopic]): Vector[PortBoundToManyTopics] =
    verifiedTopics
      .flatMap(verifiedTopic => verifiedTopic.connections.map(_ -> verifiedTopic.id))
      .groupBy { case (verifiedPort, _) => verifiedPort }
      .flatMap {
        case (verifiedPort, groupedTopicIds) =>
          val topicIds = groupedTopicIds.map { case (_, topic) => topic }
          if (topicIds.size > 1) Some(PortBoundToManyTopics(verifiedPort.portPath.toString, topicIds))
          else None
      }
      .toVector
      .sortBy(_.path)

  private def validate =
    if (problems.isEmpty) Right(this)
    else Left(problems)

  private def verifyPortNames(streamletDescriptors: Vector[StreamletDescriptor]): Vector[BlueprintProblem] =
    streamletDescriptors.flatMap { descriptor =>
      val inletProblems = descriptor.inlets.flatMap { inlet =>
        if (NameUtils.isDnsLabelCompatible(inlet.name))
          None
        else
          Some(InvalidInletName(descriptor.className, inlet.name))
      }

      val outletProblems = descriptor.outlets.flatMap { outlet =>
        if (NameUtils.isDnsLabelCompatible(outlet.name))
          None
        else
          Some(InvalidOutletName(descriptor.className, outlet.name))
      }

      inletProblems ++ outletProblems
    }

  private def verifyVolumeMounts(streamletDescriptors: Vector[StreamletDescriptor]): Vector[BlueprintProblem] = {
    val DNS1123LabelMaxLength = 63
    val separator             = java.io.File.separator
    val invalidPaths = streamletDescriptors.flatMap { descriptor =>
      descriptor.volumeMounts.map { volumeMount =>
        val invalidPath = volumeMount.path
          .split(separator)
          .find(_ == "..")
          .map(_ => BacktrackingVolumeMounthPath(descriptor.className, volumeMount.name, volumeMount.path))
        val emptyPath =
          if (volumeMount.path.isEmpty())
            Some(EmptyVolumeMountPath(descriptor.className, volumeMount.name))
          else None
        val nonAbsolutePath =
          if (!new File(volumeMount.path).toPath.isAbsolute())
            Some(NonAbsoluteVolumeMountPath(descriptor.className, volumeMount.name, volumeMount.path))
          else None

        Vector(invalidPath, emptyPath, nonAbsolutePath).flatten
      }
    }

    val invalidNames = streamletDescriptors.flatMap { descriptor =>
      descriptor.volumeMounts.map { volumeMount =>
        if (NameUtils.isDnsLabelCompatible(volumeMount.name)) {
          if (volumeMount.name.length > DNS1123LabelMaxLength)
            Some(InvalidVolumeMountName(descriptor.className, volumeMount.name))
          else
            None
        } else {
          Some(InvalidVolumeMountName(descriptor.className, volumeMount.name))
        }
      }
    }

    val duplicateNames = streamletDescriptors.flatMap { descriptor =>
      val names = descriptor.volumeMounts.map { volumeMount =>
        volumeMount.name
      }
      names.diff(names.distinct).distinct.map { name =>
        DuplicateVolumeMountName(descriptor.className, name)
      }
    }

    val duplicatePaths = streamletDescriptors.flatMap { descriptor =>
      val paths = descriptor.volumeMounts.map { volumeMount =>
        volumeMount.path
      }
      paths.diff(paths.distinct).distinct.map { path =>
        DuplicateVolumeMountPath(descriptor.className, path)
      }
    }

    invalidNames.flatten ++ invalidPaths.flatten ++ duplicateNames ++ duplicatePaths
  }

  private def verifyConfigParameters(streamletDescriptors: Vector[StreamletDescriptor]): Vector[BlueprintProblem] = {
    val ConfigParameterKeyPattern = """[a-zA-Z]+(-[a-zA-Z-0-9]+)*""".r

    val invalidConfigParametersKeyProblems = {
      streamletDescriptors.flatMap { descriptor =>
        descriptor.configParameters.map { configKey =>
          configKey.key match {
            case ConfigParameterKeyPattern(_) => None
            case _                            => Some(InvalidConfigParameterKeyName(descriptor.className, configKey.key))
          }
        }
      }
    }

    val duplicateConfigParametersKeysFound = {
      streamletDescriptors.flatMap { streamletDescriptor =>
        val keys = streamletDescriptor.configParameters.map(_.key).toVector
        keys
          .diff(keys.distinct)
          .distinct
          .map(duplicateKey => DuplicateConfigParameterKeyFound(streamletDescriptor.className, duplicateKey))
      }
    }

    val invalidDefaultValueOrPatternProblems = {
      streamletDescriptors.flatMap { descriptor =>
        descriptor.configParameters.map { configKey =>
          configKey.validationType match {
            case "string" =>
              configKey.validationPattern.flatMap { regexpString =>
                // This is a Regular expression string with validation and possibly a default value
                Try(regexpString.r) match {
                  case Success(_) =>
                    val ConfigParameterPattern = regexpString.r
                    configKey.defaultValue.flatMap {
                      case ConfigParameterPattern() => None
                      case defaultValue =>
                        Some(InvalidDefaultValueInConfigParameter(descriptor.className, configKey.key, defaultValue))
                    }
                  case Failure(_) =>
                    Some(InvalidValidationPatternConfigParameter(descriptor.className, configKey.key, regexpString))
                }
              }
            case "duration" =>
              configKey.defaultValue.fold[Option[BlueprintProblem]](None) { durationDefaultValue =>
                Try(
                  ConfigFactory
                    .parseString(s"value=${durationDefaultValue}")
                    .getDuration("value", TimeUnit.NANOSECONDS)
                ) match {
                  case Success(_) => None
                  case Failure(_) =>
                    Some(InvalidDefaultValueInConfigParameter(descriptor.className, configKey.key, durationDefaultValue))
                }
              }
            case "memorysize" =>
              configKey.defaultValue.fold[Option[BlueprintProblem]](None) { memorySizeDefaultValue =>
                Try(ConfigFactory.parseString(s"value=${memorySizeDefaultValue}").getMemorySize("value")) match {
                  case Success(_) => None
                  case Failure(_) =>
                    Some(InvalidDefaultValueInConfigParameter(descriptor.className, configKey.key, memorySizeDefaultValue))
                }
              }
            case _ => None
          }
        }
      }
    }

    invalidConfigParametersKeyProblems.flatten ++ invalidDefaultValueOrPatternProblems.flatten ++ duplicateConfigParametersKeysFound
  }
}
