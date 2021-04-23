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

import scala.collection.immutable

sealed trait BlueprintProblem

object BlueprintProblem {
  def toMessage(problem: BlueprintProblem): String =
    problem match {
      case BlueprintFormatError(reason) =>
        s"The blueprint file has an invalid format:\n $reason"
      case MissingStreamletsSection =>
        s"The blueprint.streamlets section is missing in the blueprint file."
      case AmbiguousStreamletRef(streamletRef, className) =>
        s"ClassName matching `$className` is ambiguous for streamlet name $streamletRef."
      case BacktrackingVolumeMounthPath(className, name, path) =>
        s"`$className` contains a volume mount `$name` with an invalid path `$path`, backtracking in paths are not allowed."
      case DuplicateConfigParameterKeyFound(className, keyName) =>
        s"`$className` contains a duplicate configuration parameter key, `$keyName` is used in more than one `ConfigParameter`"
      case DuplicateStreamletNamesFound(verifiedStreamlets) =>
        val duplicates = verifiedStreamlets.map(s => s"(name: ${s.name}, className: ${s.className})").mkString(", ")
        s"Duplicate streamlet names detected: ${duplicates}."
      case DuplicateVolumeMountName(className, name) =>
        s"`$className` contains volume mounts with duplicate names (`$name`)."
      case DuplicateVolumeMountPath(className, path) =>
        s"`$className` contains volume mounts with duplicate paths (`$path`)."
      case EmptyStreamletDescriptors =>
        s"The streamlet descriptor list is empty."
      case EmptyStreamlets =>
        s"The application blueprint is empty."
      case EmptyVolumeMountPath(className, name) =>
        s"`$className` contains a volume mount `$name` with an empty path."
      case InvalidConfigParameterKeyName(className, keyName) =>
        s"`$className` contains a configuration parameter with invalid key name `$keyName`."
      case InvalidDefaultValueInConfigParameter(className, keyName, defaultValue) =>
        s"`$className` contains a configuration parameter `$keyName` with an invalid default value, `$defaultValue` is invalid."
      case InvalidValidationPatternConfigParameter(className, keyName, validationPattern) =>
        s"`$className` contains a configuration parameter `$keyName` with an invalid validation pattern `$validationPattern`."
      case IncompatibleSchema(outlet, inlet) =>
        s"Outlet $outlet is not compatible with inlet $inlet."
      case InvalidInletName(className, name) =>
        s"Inlet `$name` in streamlet `$className` is invalid. Names must consist of lower case alphanumeric characters and may contain '-' except for at the start or end."
      case InvalidOutletName(className, name) =>
        s"Outlet `$name` in streamlet `$className` is invalid. Names must consist of lower case alphanumeric characters and may contain '-' except for at the start or end."
      case InvalidPortPath(path) =>
        s"'$path' is not a valid path to an outlet or an inlet."
      case InvalidTopicName(topicName) =>
        s"'$topicName' is not a valid topic name, must match '${Topic.LegalTopicChars}', max 255 characters."
      case InvalidProducerPortPath(topic, path) =>
        s"'$path' is not a valid producer for topic '$topic', must be an outlet."
      case InvalidConsumerPortPath(topic, path) =>
        s"'$path' is not a valid consumer for topic '$topic', must be an inlet."
      case InvalidStreamletName(streamletRef) =>
        s"Invalid streamlet name '$streamletRef'. Names must consist of lower case alphanumeric characters and may contain '-' except for at the start or end."
      case InvalidStreamletClassName(streamletRef, className) =>
        s"Class name '$className' for streamlet '$streamletRef' is invalid. Class names must be valid Java/Scala class names."
      case InvalidVolumeMountName(className, name) =>
        s"Volume mount `$name` in streamlet `$className` is invalid. Names must consist of lower case alphanumeric characters and may contain '-' except for at the start or end."
      case NonAbsoluteVolumeMountPath(className, name, path) =>
        s"`$className` contains a volume mount `$name` with a non-absolute path (`$path`)."
      case PortBoundToManyTopics(path, topics) =>
        s"'$path' is bound to more than one topic: ${topics.mkString(",")}."
      case PortPathNotFound(path, suggestions) =>
        val end =
          if (suggestions.nonEmpty) s""", please try ${suggestions.map(_.toString).mkString(" or ")}.""" else "."
        s"'$path' does not point to a known streamlet inlet or outlet$end"
      case InvalidKafkaClusterName(name) =>
        s"Invalid Kafka cluster name '$name'. Names must consist of lower case alphanumeric characters and may contain '-' except for at the start or end."
      case StreamletDescriptorNotFound(streamletRef, className) =>
        s"ClassName $className for $streamletRef cannot be found."
      case UnconnectedInlets(unconnectedInlets) =>
        val list = unconnectedInlets.map(ui => s"${ui.streamletRef}.${ui.port.name}").mkString(",")
        if (unconnectedInlets.size > 1) {
          s"Inlets ($list) are not connected."
        } else s"Inlet $list is not connected."
      case UnconnectedOutlets(unconnectedOutlets) =>
        val list = unconnectedOutlets.map(ui => s"${ui.streamletRef}.${ui.port.name}").mkString(",")
        if (unconnectedOutlets.size > 1) {
          s"Outlets ($list) are not connected."
        } else s"Outlet $list is not connected."
    }
}

final case class AmbiguousStreamletRef(streamletRef: String, streamletClassName: String)      extends BlueprintProblem
final case class DuplicateStreamletNamesFound(streamlets: immutable.IndexedSeq[StreamletRef]) extends BlueprintProblem
case object EmptyStreamlets                                                                   extends BlueprintProblem
case object EmptyStreamletDescriptors                                                         extends BlueprintProblem
case object MissingStreamletsSection                                                          extends BlueprintProblem
case class BlueprintFormatError(reason: String)                                               extends BlueprintProblem

sealed trait PortProblem extends BlueprintProblem {
  def path: VerifiedPortPath
}

final case class IncompatibleSchema(path: VerifiedPortPath, otherPath: VerifiedPortPath) extends PortProblem

final case class InvalidTopicName(topicName: String) extends BlueprintProblem

sealed trait PortPathError                                            extends BlueprintProblem
final case class InvalidPortPath(path: String)                        extends BlueprintProblem with PortPathError
final case class InvalidProducerPortPath(topic: String, path: String) extends BlueprintProblem with PortPathError
final case class InvalidConsumerPortPath(topic: String, path: String) extends BlueprintProblem with PortPathError
final case class PortPathNotFound(path: String, suggestions: immutable.IndexedSeq[VerifiedPortPath] = immutable.IndexedSeq.empty)
    extends PortPathError
final case class PortBoundToManyTopics(path: String, topics: immutable.IndexedSeq[String]) extends PortPathError

final case class InvalidKafkaClusterName(name: String) extends BlueprintProblem

final case class InvalidStreamletClassName(streamletRef: String, streamletClassName: String) extends BlueprintProblem
final case class InvalidStreamletName(streamletRef: String)                                  extends BlueprintProblem

final case class InvalidConfigParameterKeyName(className: String, keyName: String) extends BlueprintProblem
final case class InvalidValidationPatternConfigParameter(className: String, keyName: String, validationPattern: String)
    extends BlueprintProblem
final case class DuplicateConfigParameterKeyFound(className: String, keyName: String)                           extends BlueprintProblem
final case class InvalidDefaultValueInConfigParameter(className: String, keyName: String, defaultValue: String) extends BlueprintProblem
final case class StreamletDescriptorNotFound(streamletRef: String, streamletClassName: String)                  extends BlueprintProblem

sealed trait UnconnectedPorts extends BlueprintProblem {
  def nonEmpty: Boolean
}
final case class UnconnectedInlets(unconnectedInlets: immutable.IndexedSeq[UnconnectedPort]) extends UnconnectedPorts {
  def nonEmpty: Boolean = unconnectedInlets.nonEmpty
}
final case class UnconnectedPort(streamletRef: String, port: PortDescriptor)
final case class UnconnectedOutlets(unconnectedOutlets: immutable.IndexedSeq[UnconnectedPort]) extends UnconnectedPorts {
  def nonEmpty: Boolean = unconnectedOutlets.nonEmpty
}

final case class BacktrackingVolumeMounthPath(className: String, name: String, path: String) extends BlueprintProblem
final case class NonAbsoluteVolumeMountPath(className: String, name: String, path: String)   extends BlueprintProblem
final case class EmptyVolumeMountPath(className: String, name: String)                       extends BlueprintProblem
final case class DuplicateVolumeMountPath(className: String, path: String)                   extends BlueprintProblem
final case class InvalidVolumeMountName(className: String, name: String)                     extends BlueprintProblem
final case class DuplicateVolumeMountName(className: String, name: String)                   extends BlueprintProblem

final case class InvalidInletName(className: String, name: String)  extends BlueprintProblem
final case class InvalidOutletName(className: String, name: String) extends BlueprintProblem
