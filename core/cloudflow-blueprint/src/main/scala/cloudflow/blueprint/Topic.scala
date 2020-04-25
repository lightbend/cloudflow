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

/**
 * Defines a Topic and the streamlet inlets and outlets that connect to it.
 */
// TODO check that topic-name is valid topic-name.
final case class Topic(
    name: String,
    producers: Vector[String] = Vector.empty[String],
    consumers: Vector[String] = Vector.empty[String],
    kafkaConfig: Config = ConfigFactory.empty(),
    // override for bootstrapServers
    bootstrapServers: Option[String] = None,
    create: Boolean = true,
    problems: Vector[BlueprintProblem] = Vector.empty[BlueprintProblem],
    verified: Option[VerifiedTopic] = None
) {

  def verify(verifiedStreamlets: Vector[VerifiedStreamlet]): Topic = {

    //TODO for Topic:
    //val legalChars = "[a-zA-Z0-9\\._\\-]"
    //private val maxNameLength = 255
    //private val rgx = new Regex(legalChars + "+")

    val patternErrors               = (producers ++ consumers).flatMap(port => VerifiedPortPath(port).left.toOption)
    val verifiedProducerPaths       = producers.flatMap(producer => VerifiedPortPath(producer).toOption)
    val verifiedConsumerPaths       = consumers.flatMap(consumer => VerifiedPortPath(consumer).toOption)
    val verifiedProducerPortsResult = VerifiedPort.collectPorts(verifiedProducerPaths, verifiedStreamlets)
    val verifiedConsumerPortsResult = VerifiedPort.collectPorts(verifiedConsumerPaths, verifiedStreamlets)

    val portPathErrors = verifiedProducerPortsResult.left.toOption.getOrElse(Vector.empty[PortPathError]) ++
          verifiedConsumerPortsResult.left.toOption.getOrElse(Vector.empty[PortPathError])

    // producers must be outlets
    val producerErrors = verifiedProducerPortsResult
      .flatMap { res =>
        val inlets = res.filterNot(_.isOutlet)
        if (inlets.nonEmpty) Left(inlets.map(p => InvalidProducerPortPath(p.portPath.toString)))
        else Right(res)
      }
      .left
      .toOption
      .getOrElse(Vector.empty[PortPathError])

    // consumers must be inlets
    val consumerErrors = verifiedConsumerPortsResult
      .flatMap { res =>
        val outlets = res.filter(_.isOutlet)
        if (outlets.nonEmpty) Left(outlets.map(p => InvalidConsumerPortPath(p.portPath.toString)))
        else Right(res)
      }
      .left
      .toOption
      .getOrElse(Vector.empty[PortPathError])

    val verifiedPorts = verifiedProducerPortsResult.getOrElse(Vector.empty[VerifiedPort]) ++ verifiedConsumerPortsResult.getOrElse(
            Vector.empty[VerifiedPort]
          )
    val schemaErrors = verifySchema(verifiedPorts)
    copy(
      problems = patternErrors ++ portPathErrors ++ producerErrors ++ consumerErrors ++ schemaErrors,
      verified =
        if (verifiedPorts.nonEmpty)
          Some(VerifiedTopic(name, verifiedPorts.distinct.sortBy(_.portPath.toString), bootstrapServers, create, kafkaConfig))
        else None
    )
  }

  val connections = producers ++ consumers

  val AvroFormat = "avro"

  private def verifySchema(
      verifiedPorts: Vector[VerifiedPort]
  ): Vector[BlueprintProblem] =
    verifiedPorts
      .flatMap { port =>
        verifiedPorts.flatMap { otherPort =>
          checkCompatibility(port, otherPort)
        }
      }
      .map { schemaError =>
        val sortedPaths = Vector(schemaError.path, schemaError.otherPath).sortBy(_.toString)
        IncompatibleSchema(sortedPaths(0), sortedPaths(1))
      }
      .distinct

  private def checkCompatibility(port: VerifiedPort, otherPort: VerifiedPort): Option[IncompatibleSchema] =
    if (otherPort.portPath != port.portPath) {
      val schema      = port.schemaDescriptor
      val otherSchema = otherPort.schemaDescriptor

      if (otherSchema.format == schema.format &&
          otherSchema.fingerprint == schema.fingerprint) {
        None
      } else if (otherSchema.format == AvroFormat) {
        checkAvroCompatibility(port, otherPort)
      } else Some(IncompatibleSchema(port.portPath, otherPort.portPath))
    } else None

  private def checkAvroCompatibility(port: VerifiedPort, otherPort: VerifiedPort): Option[IncompatibleSchema] = {
    val schema      = port.schemaDescriptor
    val otherSchema = otherPort.schemaDescriptor
    if (compatibleAvroSchema(schema, otherSchema)) None
    else Some(IncompatibleSchema(port.portPath, otherPort.portPath))
  }

  /**
   * Schema resolution is done based on the information in http://avro.apache.org/docs/current/spec.html#Schema+Resolution
   * all connections should use compatible schemas
   */
  private def compatibleAvroSchema(schema: SchemaDescriptor, otherSchema: SchemaDescriptor): Boolean = {
    import org.apache.avro._

    val writerSchema = new Schema.Parser().parse(otherSchema.schema)
    val readerSchema = new Schema.Parser().parse(schema.schema)

    val result = SchemaCompatibility.checkReaderWriterCompatibility(readerSchema, writerSchema).getType
    result == SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE
  }
}
