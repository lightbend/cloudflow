/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

import org.apache.avro._

import com.typesafe.config.Config

final case class StreamletConnection(
    from: String,
    to: String,
    label: Option[String] = None,
    problems: Vector[BlueprintProblem] = Vector.empty[BlueprintProblem],
    verified: Option[VerifiedStreamletConnection] = None,
    metadata: Option[Config] = None
) {

  def verify(verifiedStreamlets: Vector[VerifiedStreamlet]): StreamletConnection = {
    val fromPathError = VerifiedPortPath(from).left.toOption
    val toPathError = VerifiedPortPath(to).left.toOption
    val patternErrors = Set(fromPathError, toPathError).flatten

    val outletResult = VerifiedOutlet.find(verifiedStreamlets, from)
    val inletResult = VerifiedInlet.find(verifiedStreamlets, to)
    val outletError = outletResult.left.toOption
    val inletError = inletResult.left.toOption

    val verifiedFromPath = outletResult.fold(_ ⇒ from, _.portPath.toString)
    val verifiedToPath = inletResult.fold(_ ⇒ to, _.portPath.toString)

    val connectionFound = for {
      verifiedOutlet ← outletResult
      verifiedInlet ← inletResult
      validConnection ← verifySchema(verifiedOutlet, verifiedInlet, label)
    } yield validConnection
    val schemaError = connectionFound.left.toOption
    val conErrors = Set(outletError, inletError, schemaError).flatten
    val verified = connectionFound.toOption
    copy(
      problems = (patternErrors ++ conErrors).toVector,
      verified = verified,
      from = verified.fold(verifiedFromPath)(_.verifiedOutlet.portPath.toString),
      to = verified.fold(verifiedToPath)(_.verifiedInlet.portPath.toString)
    )
  }

  val AvroFormat = "avro"

  /**
   * Schema resolution is done based on the information in http://avro.apache.org/docs/current/spec.html#Schema+Resolution
   */
  private def verifySchema(
      verifiedOutlet: VerifiedOutlet,
      verifiedInlet: VerifiedInlet,
      label: Option[String]
  ): Either[BlueprintProblem, VerifiedStreamletConnection] = {

    if (verifiedOutlet.schemaDescriptor.format == verifiedInlet.schemaDescriptor.format &&
      verifiedOutlet.schemaDescriptor.fingerprint == verifiedInlet.schemaDescriptor.fingerprint) {
      Right(VerifiedStreamletConnection(verifiedOutlet, verifiedInlet, label))
    } else if (verifiedOutlet.schemaDescriptor.format == AvroFormat) {
      val writerSchema = new Schema.Parser().parse(verifiedOutlet.schemaDescriptor.schema)
      val readerSchema = new Schema.Parser().parse(verifiedInlet.schemaDescriptor.schema)

      val result = SchemaCompatibility.checkReaderWriterCompatibility(readerSchema, writerSchema).getType
      if (result == SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE) Right(VerifiedStreamletConnection(verifiedOutlet, verifiedInlet, label))
      else Left(IncompatibleSchema(verifiedOutlet.portPath, verifiedInlet.portPath))
    } else Left(IncompatibleSchema(verifiedOutlet.portPath, verifiedInlet.portPath))
  }
}
