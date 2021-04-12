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
import scala.util.Try
import com.typesafe.config._
object Topic {
  val LegalTopicChars   = "[a-zA-Z0-9\\._\\-]"
  val LegalTopicPattern = s"$LegalTopicChars+".r
  val MaxLength         = 249
}

/**
 * Defines a Topic and the streamlet inlets and outlets that connect to it.
 */
// TODO check that topic-name is valid topic-name.
final case class Topic(id: String,
                       producers: Vector[String] = Vector.empty[String],
                       consumers: Vector[String] = Vector.empty[String],
                       cluster: Option[String] = None,
                       kafkaConfig: Config = ConfigFactory.empty(),
                       problems: Vector[BlueprintProblem] = Vector.empty[BlueprintProblem],
                       verified: Option[VerifiedTopic] = None) {
  def name = Try(kafkaConfig.getString("topic.name")).getOrElse(id)
  import Topic._
  def verify(verifiedStreamlets: Vector[VerifiedStreamlet]): Topic = {

    val invalidTopicError = name match {
      case LegalTopicPattern() =>
        if (name.size > MaxLength) Vector(InvalidTopicName(name)) else Vector.empty[BlueprintProblem]
      case _ => Vector(InvalidTopicName(name))
    }

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
        if (inlets.nonEmpty) Left(inlets.map(p => InvalidProducerPortPath(name, p.portPath.toString)))
        else Right(res)
      }
      .left
      .toOption
      .getOrElse(Vector.empty[PortPathError])

    // consumers must be inlets
    val consumerErrors = verifiedConsumerPortsResult
      .flatMap { res =>
        val outlets = res.filter(_.isOutlet)
        if (outlets.nonEmpty) Left(outlets.map(p => InvalidConsumerPortPath(name, p.portPath.toString)))
        else Right(res)
      }
      .left
      .toOption
      .getOrElse(Vector.empty[PortPathError])

    val clusterNameError = cluster.flatMap { clusterName =>
      if (NameUtils.isDnsLabelCompatible(clusterName)) None else Some(InvalidKafkaClusterName(clusterName))
    }.toVector

    val verifiedPorts =
      verifiedProducerPortsResult.getOrElse(Vector.empty[VerifiedPort]) ++ verifiedConsumerPortsResult.getOrElse(Vector.empty[VerifiedPort])
    val schemaErrors = verifySchema(verifiedPorts)
    copy(
      problems =
        invalidTopicError ++ patternErrors ++ portPathErrors ++ producerErrors ++ consumerErrors ++ clusterNameError ++ schemaErrors,
      verified =
        if (verifiedPorts.nonEmpty)
          Some(VerifiedTopic(id, verifiedPorts.distinct.sortBy(_.portPath.toString), cluster, kafkaConfig))
        else None
    )
  }

  val connections = producers ++ consumers

  // TODO decouple this from avro, any schema can be used. It should be easy for users to register
  // their own compatibility checks.
  val AvroFormat  = "avro"
  val ProtoFormat = "proto"

  private def verifySchema(verifiedPorts: Vector[VerifiedPort]): Vector[BlueprintProblem] =
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
      // TODO make this more dynamic / pluggable
      if (otherSchema.format == schema.format &&
          otherSchema.fingerprint == schema.fingerprint) {
        None
      } else if (otherSchema.format == AvroFormat && schema.format == AvroFormat) {
        checkAvroCompatibility(port, otherPort)
      } else if (otherSchema.format == ProtoFormat && schema.format == ProtoFormat) {
        checkProtoCompatibility(port, otherPort)
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

  import com.google.protobuf.descriptor.{ DescriptorProto, FieldDescriptorProto }

  /**
   * As protobuf is a positional language, validation is done based on the field IDs.
   * Message compatibility implementation is based on Protobuf language guide and
   * considers message A to be compatible with message B if message B contains all the ids used in message A and the fields are compatible.
   *
   * The fields are considered to be compatible if:
   *
   * Field names are the same (this is required to make sure that processing application using names to access fields does not break)
   * Field types are the same (technically, according to documentation integer types int32, uint32, int64, uint64, and bool are all compatible,
   * such change in type might break consuming applications).
   *
   * Additionally, if the type is message, we validate that the message name is the same, but do not do recursive validation.
   *
   * Field in both messages have to both either have oneof_index or None.
   * The value of the index (if exists is not validated)
   * Note Be careful when adding or removing oneof fields. If checking the value of a oneof returns None/NOT_SET,
   * it could mean that the oneof has not been set or it has been set to a field in a different version of the oneof.
   * There is no way to tell the difference, since there's no way to know if an unknown field on the wire is a member of the oneof.
   * Although technically optional fields (only type supported by proto3) is compatible with repeated,
   * it might impact consuming code, so in this implementation we consider them incompatible.
   *
   * Known limitations
   *
   * Maps usage always generate incompatible messages. The issue here is when maps are used, they generate nested types,
   * which are associated with the current message and are defined based on the maps key/value.
   * Even if the maps are identical, nested types names are based on surrounding messages.
   */
  private def checkProtoCompatibility(port: VerifiedPort, otherPort: VerifiedPort): Option[IncompatibleSchema] = {
    val descriptorProtoString      = port.schemaDescriptor.schema
    val otherDescriptorProtoString = otherPort.schemaDescriptor.schema
    val descriptor                 = DescriptorProto.parseFrom(descriptorProtoString.getBytes("UTF8"))
    val otherDescriptor            = DescriptorProto.parseFrom(otherDescriptorProtoString.getBytes("UTF8"))

    if (compatibleProtobufDescriptor(descriptor, otherDescriptor)) None
    else Some(IncompatibleSchema(port.portPath, otherPort.portPath))
  }

  private def compatibleProtobufDescriptor(m1: DescriptorProto, m2: DescriptorProto): Boolean = {
    val m1Map = m1.field.map(f => (f.number.getOrElse(0), f)).toMap
    val m2Map = m2.field.map(f => (f.number.getOrElse(0), f)).toMap
    m1Map
      .map {
        case (id, field) => {
          m2Map.get(id) match {
            case Some(value) => FieldComparator.compare(field, value)
            case _           => false
          }
        }
      }
      .foldLeft(true)(_ && _)
  }

  object FieldComparator {
    def compare(f1: FieldDescriptorProto, f2: FieldDescriptorProto): Boolean =
      (f1.name == f2.name) &&
        (f1.typeName == f2.typeName) &&
        (f1.label == f2.label) &&
        (f1.`type` == f2.`type`) &&
        ((f1.oneofIndex.isDefined && f2.oneofIndex.isDefined) || (f1.oneofIndex.isEmpty && f2.oneofIndex.isEmpty))
  }
}
