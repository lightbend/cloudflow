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

import com.typesafe.config._

final case class StreamletRef(
    name: String,
    className: String,
    problems: Vector[BlueprintProblem] = Vector.empty[BlueprintProblem],
    verified: Option[VerifiedStreamlet] = None,
    metadata: Option[Config] = None,
    inletRefs: Vector[InletRef] = Vector.empty[InletRef],
    outletRefs: Vector[OutletRef] = Vector.empty[OutletRef]
) {
  private final val ClassNamePattern = """([\p{L}_$][\p{L}\p{N}_$]*\.)*[\p{L}_$][\p{L}\p{N}_$]*""".r

  def verify(streamletDescriptors: Vector[StreamletDescriptor]): StreamletRef = {
    val nameProblem =
      if (NameUtils.isDnsLabelCompatible(name)) None else Some(InvalidStreamletName(name))

    val refProblem = className match {
      case ClassNamePattern(_) ⇒ None
      case _                   ⇒ Some(InvalidStreamletClassName(name, className))
    }

    val descriptorFound: Either[BlueprintProblem, StreamletDescriptor] =
      streamletDescriptors.find(_.className == className) match {
        case Some(streamlet) ⇒ Right(streamlet)
        case None ⇒ {
          val matchingPartially = streamletDescriptors.filter(_.className.contains(className))

          if (matchingPartially.size == 1) {
            Right(matchingPartially.head)
          } else if (matchingPartially.size > 1) {
            Left(AmbiguousStreamletRef(name, className))
          } else {
            Left(StreamletDescriptorNotFound(name, className))
          }
        }
      }

    copy(
      className = descriptorFound.toOption.map(_.className).getOrElse(this.className), // use the raw value as found in the blueprint
      problems = Vector(nameProblem, refProblem).flatten ++ descriptorFound.left.toSeq ++ inletRefs.flatMap(_.problems) ++ outletRefs
              .flatMap(_.problems),
      verified = descriptorFound.toOption.map(descriptor ⇒ VerifiedStreamlet(name, descriptor))
    )
  }
}
object InletRef {
  def apply(
      inletName: String,
      bootstrapServers: String,
      topic: String,
      streamletRefName: String,
      className: String,
      consumerConfig: Config
  ): InletRef = {
    var problems = Vector.empty[BlueprintProblem]
    if (bootstrapServers.isEmpty) {
      problems = problems :+ InvalidInletRef(streamletRefName, className, inletName, s"'${Blueprint.BootstrapServersKey}' is missing.")
    }
    if (topic.isEmpty) {
      problems = problems :+ InvalidInletRef(streamletRefName, className, inletName, s"'${Blueprint.TopicKey}' is missing.")
    }

    InletRef(
      inletName,
      bootstrapServers,
      topic,
      streamletRefName,
      className,
      consumerConfig,
      problems
    )
  }
}
final case class InletRef(
    inletName: String,
    bootstrapServers: String,
    topic: String,
    streamletRefName: String,
    className: String,
    consumerConfig: Config,
    problems: Vector[BlueprintProblem]
)

object OutletRef {
  def apply(
      outletName: String,
      bootstrapServers: String,
      topic: String,
      streamletRefName: String,
      className: String,
      producerConfig: Config
  ): OutletRef = {
    var problems = Vector.empty[BlueprintProblem]
    if (bootstrapServers.isEmpty) {
      problems = problems :+ InvalidOutletRef(streamletRefName, className, outletName, s"'${Blueprint.BootstrapServersKey}' is missing.")
    }
    if (topic.isEmpty) {
      problems = problems :+ InvalidOutletRef(streamletRefName, className, outletName, s"'${Blueprint.TopicKey}' is missing.")
    }
    OutletRef(
      outletName,
      bootstrapServers,
      topic,
      streamletRefName,
      className,
      producerConfig,
      problems
    )
  }
}

final case class OutletRef(
    outletName: String,
    bootstrapServers: String,
    topic: String,
    streamletRefName: String,
    className: String,
    producerConfig: Config,
    problems: Vector[BlueprintProblem] = Vector.empty[BlueprintProblem]
)
