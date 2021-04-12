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

import com.typesafe.config._

final case class StreamletRef(name: String,
                              className: String,
                              problems: Vector[BlueprintProblem] = Vector.empty[BlueprintProblem],
                              verified: Option[VerifiedStreamlet] = None,
                              metadata: Option[Config] = None) {
  private final val ClassNamePattern = """([\p{L}_$][\p{L}\p{N}_$]*\.)*[\p{L}_$][\p{L}\p{N}_$]*""".r

  def verify(streamletDescriptors: Vector[StreamletDescriptor]): StreamletRef = {
    val nameProblem =
      if (NameUtils.isDnsLabelCompatible(name)) None else Some(InvalidStreamletName(name))

    val refProblem = className match {
      case ClassNamePattern(_) => None
      case _                   => Some(InvalidStreamletClassName(name, className))
    }
    val foundDescriptor = streamletDescriptors.find(_.className == className)
    val descriptorFound: Either[BlueprintProblem, StreamletDescriptor] =
      foundDescriptor match {
        case Some(streamlet) => Right(streamlet)
        case None => {
          val matchingPartially = streamletDescriptors.filter { descriptor =>
            descriptor.className == className ||
            descriptor.className == s"$className$$"
          }

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
      problems = Vector(nameProblem, refProblem).flatten ++ descriptorFound.left.toSeq,
      verified = descriptorFound.toOption.map(descriptor => VerifiedStreamlet(name, descriptor))
    )
  }
}
