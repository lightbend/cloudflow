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

package cloudflow.blueprint

import java.io._
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.util._

import com.typesafe.config._

object Blueprint {
  val StreamletsSectionKey  = "blueprint.streamlets"
  val ConnectionsSectionKey = "blueprint.connections"
  val TopicKey              = "topic"
  val BootstrapServersKey   = "bootstrap.servers"

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
        val streamletRefs = getKeys(config, StreamletsSectionKey).map { key ⇒
          val simpleClassKey = s"$StreamletsSectionKey.${key}"
          val classKey       = s"$StreamletsSectionKey.${key}.class"
          val className =
            if (config.hasPath(classKey)) config.getString(classKey)
            else config.getString(simpleClassKey)

          val inletsKey  = s"$StreamletsSectionKey.${key}.inlets"
          val outletsKey = s"$StreamletsSectionKey.${key}.outlets"
          val inletRefs  = parseInletRefs(config, inletsKey, key, className)
          val outletRefs = parseOutletRefs(config, outletsKey, key, className)

          StreamletRef(
            name = key,
            className = className,
            inletRefs = inletRefs,
            outletRefs = outletRefs
          )
        }.toVector

        val streamletConnections = if (config.hasPath(ConnectionsSectionKey)) {
          config
            .getConfig(ConnectionsSectionKey)
            .entrySet
            .asScala
            .map(_.getKey)
            .flatMap { key ⇒
              val inlets = config.getStringList(s"$ConnectionsSectionKey.${key}").asScala
              inlets.map { inlet ⇒
                StreamletConnection(
                  from = key,
                  to = inlet
                )
              }
            }
            .toVector
        } else Vector.empty[StreamletConnection]
        Blueprint(streamletRefs, streamletConnections, streamletDescriptors).verify
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

  private def parseInletRefs(config: Config, inletsKey: String, streamletRefName: String, className: String) =
    if (config.hasPath(inletsKey)) {
      val inletsConfig = config.getConfig(inletsKey)
      val inletNames   = getKeys(config, inletsKey)
      inletNames.map { inletName =>
        val inletConfig      = inletsConfig.getConfig(inletName)
        val bootstrapServers = getStringOrEmpty(inletConfig, BootstrapServersKey)
        // TODO make sure producer-config is not allowed here
        val consumerConfig   = getConfigOrEmpty(inletConfig, "consumer-config")
        val topic            = getStringOrEmpty(inletConfig, TopicKey)
        InletRef(inletName, bootstrapServers, topic, streamletRefName, className, consumerConfig)
      }
    } else Vector.empty[InletRef]

  private def parseOutletRefs(config: Config, outletsKey: String, streamletRefName: String, className: String) =
    if (config.hasPath(outletsKey)) {
      val outletsConfig = config.getConfig(outletsKey)
      val outletNames   = getKeys(config, outletsKey)
      outletNames.map { outletName =>
        val outletConfig     = outletsConfig.getConfig(outletName)
        val bootstrapServers = getStringOrEmpty(outletConfig, BootstrapServersKey)
        // TODO make sure consumer-config is not allowed here
        val producerConfig   = getConfigOrEmpty(outletConfig, "producer-config")
        val topic            = getStringOrEmpty(outletConfig, TopicKey)
        OutletRef(outletName, bootstrapServers, topic, streamletRefName, className, producerConfig)
      }
    } else Vector.empty[OutletRef]

  private def getStringOrEmpty(config: Config, key: String): String = if (config.hasPath(key)) config.getString(key) else ""
  private def getConfigOrEmpty(config: Config, key: String): Config =
    if (config.hasPath(key)) config.getConfig(key) else ConfigFactory.empty()
}

final case class Blueprint(
    streamlets: Vector[StreamletRef] = Vector.empty[StreamletRef],
    connections: Vector[StreamletConnection] = Vector.empty[StreamletConnection],
    streamletDescriptors: Vector[StreamletDescriptor] = Vector.empty,
    globalProblems: Vector[BlueprintProblem] = Vector.empty[BlueprintProblem]
) {
  val problems = globalProblems ++ streamlets.flatMap(_.problems) ++ connections.flatMap(_.problems)

  val isValid = problems.isEmpty

  def define(streamletDescriptorsUpdated: Vector[StreamletDescriptor]): Blueprint =
    copy(
      streamletDescriptors = streamletDescriptorsUpdated
    ).verify

  def upsertStreamletRef(
      streamletRef: String,
      className: Option[String] = None,
      metadata: Option[Config] = None
  ): Blueprint =
    streamlets
      .find(_.name == streamletRef)
      .map { streamletRef ⇒
        val streamletRefWithClassNameUpdated =
          className
            .map(r ⇒ streamletRef.copy(className = r))
            .getOrElse(streamletRef)
        val streamletRefWithMetadataUpdated =
          metadata
            .map(_ ⇒ streamletRefWithClassNameUpdated.copy(metadata = metadata))
            .getOrElse(streamletRefWithClassNameUpdated)

        copy(streamlets = streamlets.filterNot(_.name == streamletRef.name) :+ streamletRefWithMetadataUpdated).verify
      }
      .getOrElse(
        className
          .map(streamletDescriptorRef ⇒ use(StreamletRef(name = streamletRef, className = streamletDescriptorRef, metadata = metadata)))
          .getOrElse(this)
      )

  def use(streamletRef: StreamletRef): Blueprint =
    copy(streamlets = streamlets.filterNot(_.name == streamletRef.name) :+ streamletRef).verify

  def remove(streamletRef: String): Blueprint = {
    val remainingConnections = connections.filterNot { con ⇒
      VerifiedPortPath(con.from).exists(_.streamletRef == streamletRef) ||
      VerifiedPortPath(con.to).exists(_.streamletRef == streamletRef)
    }

    copy(
      connections = remainingConnections,
      streamlets = streamlets.filterNot(_.name == streamletRef)
    ).verify
  }

  def connect(from: String, to: String): Blueprint =
    connect(StreamletConnection(from, to))

  def connect(connection: StreamletConnection): Blueprint = {
    val verifiedConnection = connection.verify(streamlets.flatMap(_.verified))
    val otherConnections   = connections.filterNot(con ⇒ con.from == verifiedConnection.from && con.to == verifiedConnection.to)
    copy(connections = otherConnections :+ verifiedConnection).verify
  }

  def disconnect(inletPortPath: String): Blueprint = {

    val verifiedStreamlets = streamlets.flatMap(_.verified)
    val verifiedPortPath   = VerifiedPortPath(inletPortPath)
    val verifiedInlet      = VerifiedInlet.find(verifiedStreamlets, inletPortPath)

    val toBeDeleted: StreamletConnection ⇒ Boolean =
      if (verifiedInlet.isRight) { con: StreamletConnection ⇒
        VerifiedPortPath(con.to) == verifiedInlet.map(_.portPath)
      } else if (verifiedPortPath.isRight) { con: StreamletConnection ⇒
        VerifiedPortPath(con.to) == verifiedPortPath
      } else { con: StreamletConnection ⇒
        con.to == inletPortPath
      }

    copy(connections = connections.filterNot(toBeDeleted)).verify
  }

  def verify = {
    val emptyStreamletsProblem           = if (streamlets.isEmpty) Some(EmptyStreamlets) else None
    val emptyStreamletDescriptorsProblem = if (streamletDescriptors.isEmpty) Some(EmptyStreamletDescriptors) else None

    val newStreamlets      = streamlets.map(_.verify(streamletDescriptors))
    val verifiedStreamlets = newStreamlets.flatMap(_.verified)

    val newConnections      = connections.map(_.verify(verifiedStreamlets))
    val verifiedConnections = newConnections.flatMap(_.verified)

    val duplicatesProblem = verifyNoDuplicateStreamletNames(newStreamlets).left.toOption

    val portNameProblems        = verifyPortNames(streamletDescriptors)
    val configParameterProblems = verifyConfigParameters(streamletDescriptors)
    val volumeMountProblems     = verifyVolumeMounts(streamletDescriptors)

    val illegalConnectionProblems =
      verifyUniqueInletConnections(verifiedConnections)
        .fold(identity, _ ⇒ Vector.empty[InletProblem])

    val inletProblems: Vector[InletProblem] = illegalConnectionProblems ++
          newConnections.flatMap { con ⇒
            con.problems.collect {
              case p: InletProblem ⇒ p
            }
          }

    val unconnectedInletProblems = verifyInletsConnected(verifiedStreamlets, verifiedConnections)
      .fold(identity, _ ⇒ Vector.empty)
      .flatMap { problem ⇒
        val filteredUnconnectedInlets = problem.unconnectedInlets.filterNot { unconnectedInlet ⇒
          inletProblems.exists(_.inletPath == VerifiedPortPath(unconnectedInlet.streamletRef, Some(unconnectedInlet.inlet.name)))
        }
        if (filteredUnconnectedInlets.nonEmpty) Some(problem.copy(unconnectedInlets = filteredUnconnectedInlets))
        else None
      }

    val globalProblems = Vector(
        emptyStreamletsProblem,
        emptyStreamletDescriptorsProblem,
        duplicatesProblem
      ).flatten ++ illegalConnectionProblems ++ unconnectedInletProblems ++ portNameProblems ++ configParameterProblems ++ volumeMountProblems

    copy(
      streamlets = newStreamlets,
      connections = newConnections,
      globalProblems = globalProblems
    )
  }

  def verified: Either[Vector[BlueprintProblem], VerifiedBlueprint] =
    for {
      validBlueprint ← verify.validate
    } yield VerifiedBlueprint(validBlueprint.streamlets.flatMap(_.verified), validBlueprint.connections.flatMap(_.verified))

  private def verifyNoDuplicateStreamletNames(
      streamlets: Vector[StreamletRef]
  ): Either[DuplicateStreamletNamesFound, Vector[StreamletRef]] = {
    val dups =
      streamlets
        .groupBy(_.name.trim())
        .flatMap {
          case (_, duplicateStreamlets) if duplicateStreamlets.size > 1 ⇒ duplicateStreamlets
          case _                                                        ⇒ Vector.empty[StreamletRef]
        }
    if (dups.isEmpty) Right(streamlets)
    else Left(DuplicateStreamletNamesFound(dups.toVector))
  }

  private def verifyUniqueInletConnections(
      verifiedStreamletConnections: Vector[VerifiedStreamletConnection]
  ): Either[Vector[InletProblem], Vector[VerifiedStreamletConnection]] = {
    val illegalConnectionProblems = verifiedStreamletConnections
      .groupBy(_.verifiedInlet)
      .collect {
        case (inlet, cons) if cons.size > 1 ⇒ IllegalConnection(cons.map(_.verifiedOutlet.portPath), inlet.portPath)
      }
      .toVector
    if (illegalConnectionProblems.nonEmpty) Left(illegalConnectionProblems)
    else Right(verifiedStreamletConnections)
  }

  private def verifyInletsConnected(
      verifiedStreamlets: Vector[VerifiedStreamlet],
      verifiedStreamletConnections: Vector[VerifiedStreamletConnection]
  ): Either[Vector[UnconnectedInlets], Vector[VerifiedStreamlet]] = {
    val unconnectedPortProblems = verifiedStreamlets.flatMap { streamlet ⇒
      val unconnectedInlets = streamlet.descriptor.inlets
        .filterNot { inlet ⇒
          verifiedStreamletConnections.exists(con ⇒ con.verifiedInlet.streamlet == streamlet && con.verifiedInlet.portName == inlet.name) ||
          verifiedStreamlets.exists(_.inletRefs.exists(_.inletName == inlet.name))
        }
        .map(inlet ⇒ UnconnectedInlet(streamlet.name, inlet))

      if (unconnectedInlets.nonEmpty) {
        Some(UnconnectedInlets(unconnectedInlets))
      } else None
    }
    if (unconnectedPortProblems.nonEmpty) Left(unconnectedPortProblems)
    else Right(verifiedStreamlets)
  }

  private def validate =
    if (problems.isEmpty) Right(this)
    else Left(problems)

  private def verifyPortNames(streamletDescriptors: Vector[StreamletDescriptor]): Vector[BlueprintProblem] =
    streamletDescriptors.flatMap { descriptor ⇒
      val inletProblems = descriptor.inlets.flatMap { inlet ⇒
        if (NameUtils.isDnsLabelCompatible(inlet.name))
          None
        else
          Some(InvalidInletName(descriptor.className, inlet.name))
      }

      val outletProblems = descriptor.outlets.flatMap { outlet ⇒
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
    val invalidPaths = streamletDescriptors.flatMap { descriptor ⇒
      descriptor.volumeMounts.map { volumeMount ⇒
        val invalidPath = volumeMount.path
          .split(separator)
          .find(_ == "..")
          .map(_ ⇒ BacktrackingVolumeMounthPath(descriptor.className, volumeMount.name, volumeMount.path))
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

    val invalidNames = streamletDescriptors.flatMap { descriptor ⇒
      descriptor.volumeMounts.map { volumeMount ⇒
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

    val duplicateNames = streamletDescriptors.flatMap { descriptor ⇒
      val names = descriptor.volumeMounts.map { volumeMount ⇒
        volumeMount.name
      }
      names.diff(names.distinct).distinct.map { name ⇒
        DuplicateVolumeMountName(descriptor.className, name)
      }
    }

    val duplicatePaths = streamletDescriptors.flatMap { descriptor ⇒
      val paths = descriptor.volumeMounts.map { volumeMount ⇒
        volumeMount.path
      }
      paths.diff(paths.distinct).distinct.map { path ⇒
        DuplicateVolumeMountPath(descriptor.className, path)
      }
    }

    invalidNames.flatten ++ invalidPaths.flatten ++ duplicateNames ++ duplicatePaths
  }

  private def verifyConfigParameters(streamletDescriptors: Vector[StreamletDescriptor]): Vector[BlueprintProblem] = {
    val ConfigParameterKeyPattern = """[a-zA-Z]+(-[a-zA-Z-0-9]+)*""".r

    val invalidConfigParametersKeyProblems = {
      streamletDescriptors.flatMap { descriptor ⇒
        descriptor.configParameters.map { configKey ⇒
          configKey.key match {
            case ConfigParameterKeyPattern(_) ⇒ None
            case _                            ⇒ Some(InvalidConfigParameterKeyName(descriptor.className, configKey.key))
          }
        }
      }
    }

    val duplicateConfigParametersKeysFound = {
      streamletDescriptors.flatMap { streamletDescriptor ⇒
        val keys = streamletDescriptor.configParameters.map(_.key).toVector
        keys.diff(keys.distinct).distinct.map(duplicateKey ⇒ DuplicateConfigParameterKeyFound(streamletDescriptor.className, duplicateKey))
      }
    }

    val invalidDefaultValueOrPatternProblems = {
      streamletDescriptors.flatMap { descriptor ⇒
        descriptor.configParameters.map { configKey ⇒
          configKey.validationType match {
            case "string" ⇒
              configKey.validationPattern.flatMap { regexpString ⇒
                // This is a Regular expression string with validation and possibly a default value
                Try(regexpString.r) match {
                  case Success(_) ⇒
                    val ConfigParameterPattern = regexpString.r
                    configKey.defaultValue.flatMap {
                      case ConfigParameterPattern() ⇒ None
                      case defaultValue             ⇒ Some(InvalidDefaultValueInConfigParameter(descriptor.className, configKey.key, defaultValue))
                    }
                  case Failure(_) ⇒
                    Some(InvalidValidationPatternConfigParameter(descriptor.className, configKey.key, regexpString))
                }
              }
            case "duration" ⇒
              configKey.defaultValue.fold[Option[BlueprintProblem]](None) { durationDefaultValue ⇒
                Try(ConfigFactory.parseString(s"value=${durationDefaultValue}").getDuration("value", TimeUnit.NANOSECONDS)) match {
                  case Success(_) ⇒ None
                  case Failure(_) ⇒
                    Some(InvalidDefaultValueInConfigParameter(descriptor.className, configKey.key, durationDefaultValue))
                }
              }
            case "memorysize" ⇒
              configKey.defaultValue.fold[Option[BlueprintProblem]](None) { memorySizeDefaultValue ⇒
                Try(ConfigFactory.parseString(s"value=${memorySizeDefaultValue}").getMemorySize("value")) match {
                  case Success(_) ⇒ None
                  case Failure(_) ⇒
                    Some(InvalidDefaultValueInConfigParameter(descriptor.className, configKey.key, memorySizeDefaultValue))
                }
              }
            case _ ⇒ None
          }
        }
      }
    }

    invalidConfigParametersKeyProblems.flatten ++ invalidDefaultValueOrPatternProblems.flatten ++ duplicateConfigParametersKeysFound
  }
}
