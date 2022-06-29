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

import java.util.Base64

import scala.reflect._

import com.sksamuel.avro4s._
import com.typesafe.config._
import org.apache.avro._
import org.apache.avro.SchemaNormalization._
import org.scalatest._

object StreamletDescriptorBuilder extends StreamletDescriptorBuilder

/**
 * Builds [[StreamletDescriptor]]s for testing purposes.
 */
trait StreamletDescriptorBuilder extends EitherValues with OptionValues {
  private def randomClassName = "C" + java.util.UUID.randomUUID().toString.replaceAll("-", "") // TODO gen

  /**
   * Creates a random streamlet (descriptor) which can be further modified with the builder methods in [[StreamletDescriptorBuilderOps]].
   */
  def randomStreamlet(): StreamletDescriptor = buildStreamletDescriptor(randomClassName)
  def randomStreamlet(runtime: String): StreamletDescriptor = buildStreamletDescriptor(randomClassName, runtime)

  /**
   * Creates a streamlet (descriptor) which can be further modified with the builder methods in [[StreamletDescriptorBuilderOps]].
   */
  def streamlet(className: String, runtime: String): StreamletDescriptor = buildStreamletDescriptor(className, runtime)
  def streamlet(className: String): StreamletDescriptor = buildStreamletDescriptor(className)

  /**
   * Adds builder methods to [[StreamletDescriptor]] for ease of testing. (In the docs, `StreamletDescriptor` and `streamlet` are used interchangeably.)
   * Start with a `randomStreamlet()` or `streamlet(name)` in [[BlueprintBuilder]] to create a streamlet, which
   * can then be easily modified by using `asIngress`, `asProcessor`, ... to a shape that is required for testing.
   */
  implicit class StreamletDescriptorBuilderOps(streamletDescriptor: StreamletDescriptor) {

    /** Transforms the descriptor into a streamlet that has no connections (just for testing) */
    def asBox = streamletDescriptor.copy(outlets = Vector.empty, inlets = Vector.empty)

    /** Transforms the descriptor into an ingress */
    def asIngress(outlets: Vector[OutletDescriptor]) =
      streamletDescriptor.copy(outlets = outlets, inlets = Vector.empty)

    // helpers to quickly get to well known inlets and outlets
    // using `lift` to get optional result, so scalatest value can be used, for more information on failure.
    def outlet = streamletDescriptor.outlets.lift(0).value
    def inlet = streamletDescriptor.inlets.lift(0).value
    def in = inlet
    def out = outlet
    def in0 = inlet
    def in1 = streamletDescriptor.inlets.lift(1).value

    /**
     * Transforms the streamlet into an ingress. A schema is auto generated for `T` and used in the outlet.
     */
    def asIngress[T: ClassTag: SchemaFor](outletName: String = "out") = {
      val schemaName = classTag[T].runtimeClass.getName
      streamletDescriptor.copy(outlets = Vector(createOutletDescriptor(outletName, schemaName)), inlets = Vector.empty)
    }

    def asIngress[T: ClassTag: SchemaFor]: StreamletDescriptor = asIngress[T]()

    /**
     * Transforms the streamlet into a processor. A schema is auto generated for `I` and `O`, used in the outlet and inlet.
     */
    def asProcessor[I: ClassTag: SchemaFor, O: ClassTag: SchemaFor](
        inletName: String = "in",
        outletName: String = "out") = {
      val inletSchemaName = classTag[I].runtimeClass.getName
      val outletSchemaName = classTag[O].runtimeClass.getName
      streamletDescriptor.copy(
        outlets = Vector(createOutletDescriptor[O](outletName, outletSchemaName)),
        inlets = Vector(createInletDescriptor[I](inletName, inletSchemaName)))
    }

    def asProcessor[I: ClassTag: SchemaFor, O: ClassTag: SchemaFor]: StreamletDescriptor = asProcessor[I, O]()

    /**
     * Transforms the streamlet into an egress. A schema is auto generated for `T`, used in the inlet.
     */
    def asEgress[T: ClassTag: SchemaFor]: StreamletDescriptor = asEgress()
    def asEgress[T: ClassTag: SchemaFor](inletName: String = "in"): StreamletDescriptor = {
      val schemaName = classTag[T].runtimeClass.getName
      streamletDescriptor.copy(outlets = Vector.empty, inlets = Vector(createInletDescriptor(inletName, schemaName)))
    }

    /**
     * Transforms the streamlet into a merge. A schema is auto generated for `I0`, `I1`, `O`, used as inlets and outlet respectively.
     */
    def asMerge[I0: ClassTag: SchemaFor, I1: ClassTag: SchemaFor, O: ClassTag: SchemaFor]: StreamletDescriptor =
      asMerge[I0, I1, O]()

    def asMerge[I0: ClassTag: SchemaFor, I1: ClassTag: SchemaFor, O: ClassTag: SchemaFor](
        inletName0: String = "in-0",
        inletName1: String = "in-1",
        outletName: String = "out") = {
      val inletSchemaName0 = classTag[I0].runtimeClass.getName
      val inletSchemaName1 = classTag[I1].runtimeClass.getName
      val outletSchemaName = classTag[O].runtimeClass.getName
      streamletDescriptor.copy(
        outlets = Vector(createOutletDescriptor[O](outletName, outletSchemaName)),
        inlets = Vector(
          createInletDescriptor[I0](inletName0, inletSchemaName0),
          createInletDescriptor[I1](inletName1, inletSchemaName1)))
    }

    /**
     * Transforms the streamlet into a splitter. A schema is auto generated for `I0`, `O0`, `O1`, used as inlet and outlets respectively.
     */
    def asSplitter[I: ClassTag: SchemaFor, O0: ClassTag: SchemaFor, O1: ClassTag: SchemaFor]: StreamletDescriptor =
      asSplitter[I, O0, O1]()

    def asSplitter[I: ClassTag: SchemaFor, O0: ClassTag: SchemaFor, O1: ClassTag: SchemaFor](
        inletName: String = "in",
        outletName0: String = "out-0",
        outletName1: String = "out-1") = {
      val inletSchemaName = classTag[I].runtimeClass.getName
      val outletSchemaName0 = classTag[O0].runtimeClass.getName
      val outletSchemaName1 = classTag[O1].runtimeClass.getName
      streamletDescriptor.copy(
        outlets = Vector(
          createOutletDescriptor[O0](outletName0, outletSchemaName0),
          createOutletDescriptor[O1](outletName1, outletSchemaName1)),
        inlets = Vector(createInletDescriptor[I](inletName, inletSchemaName)))
    }

    /**
     * Adds volume mounts to the streamlet.
     */
    def withVolumeMounts(volumeMounts: VolumeMountDescriptor*): StreamletDescriptor =
      streamletDescriptor.copy(volumeMounts = volumeMounts.toVector)

    /**
     * Adds config parameters to the streamlet.
     */
    def withConfigParameters(descriptors: ConfigParameterDescriptor*): StreamletDescriptor =
      streamletDescriptor.copy(configParameters = descriptors.toVector)

    /**
     * Adds attributes to the streamlet.
     */
    def withAttributes(attributes: Vector[StreamletAttributeDescriptor]): StreamletDescriptor =
      streamletDescriptor.copy(attributes = attributes)

    def withRuntime(runtime: String): StreamletDescriptor =
      streamletDescriptor.copy(runtime = StreamletRuntimeDescriptor(runtime))

    /**
     * Adds a server attribute to the streamlet.
     */
    def withServerAttribute: StreamletDescriptor =
      withAttributes(Vector(StreamletAttributeDescriptor("server", "cloudflow.internal.server.container-port")))

    /** creates a random reference name*/
    def randomRefName: String = "i" + java.util.UUID.randomUUID.toString // TODO use gen

    /**
     * Creates a [[StreamletRef]] reference to this streamlet.
     */
    def ref(refName: String, metadata: Option[Config] = None): StreamletRef =
      StreamletRef(refName, streamletDescriptor.className, metadata = metadata)

    /**
     * Creates a random [[StreamletRef]] reference to this streamlet.
     */
    def randomRef: StreamletRef = ref(randomRefName) // TODO use gen
  }

  /**
   * Creates a streamlet descriptor from defaults. Use the [[StreamletDescriptorBuilderOps]] builder methods to modify.
   */
  def buildStreamletDescriptor(className: String, runtime: String): StreamletDescriptor =
    StreamletDescriptor(
      className = className,
      runtime = StreamletRuntimeDescriptor(runtime),
      labels = Vector.empty,
      description = "",
      inlets = Vector.empty,
      outlets = Vector.empty,
      configParameters = Vector.empty,
      attributes = Vector.empty,
      volumeMounts = Vector.empty)

  def buildStreamletDescriptor(
      className: String,
      runtime: StreamletRuntimeDescriptor,
      labels: Vector[String],
      description: String,
      inlets: Vector[InletDescriptor],
      outlets: Vector[OutletDescriptor],
      configParameters: Vector[ConfigParameterDescriptor],
      attributes: Vector[StreamletAttributeDescriptor],
      volumeMounts: Vector[VolumeMountDescriptor]): StreamletDescriptor =
    StreamletDescriptor(
      className = className,
      runtime = runtime,
      labels = labels,
      description = description,
      inlets = inlets,
      outlets = outlets,
      configParameters = configParameters,
      attributes = attributes,
      volumeMounts = volumeMounts)

  /**
   * Creates a streamlet descriptor from defaults. Use the StreamletDescriptorBuilderOps to modify.
   */
  def buildStreamletDescriptor(className: String): StreamletDescriptor =
    StreamletDescriptor(
      className = className,
      runtime = StreamletRuntimeDescriptor("akka"),
      labels = Vector.empty,
      description = "",
      inlets = Vector.empty,
      outlets = Vector.empty,
      configParameters = Vector.empty,
      attributes = Vector.empty,
      volumeMounts = Vector.empty)

  def createInletDescriptor[T: ClassTag: SchemaFor](name: String, schemaName: String) =
    InletDescriptor(name, createSchemaDescriptor(schemaName))

  def createOutletDescriptor[T: ClassTag: SchemaFor](name: String, schemaName: String) =
    OutletDescriptor(name, createSchemaDescriptor(schemaName))

  def createSchemaDescriptor[T: ClassTag: SchemaFor](schemaName: String) = {
    implicit val schema = implicitly[SchemaFor[T]].schema
    SchemaDescriptor(schemaName, schema.toString, fingerprintSha256(schema), "avro")
  }

  private def fingerprintSha256(schema: Schema): String =
    Base64
      .getEncoder()
      .encodeToString(parsingFingerprint("SHA-256", schema))
}
