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

case class VerifiedBlueprint(
    streamlets: Vector[VerifiedStreamlet],
    connections: Vector[VerifiedStreamletConnection]
) {
  def findOutlet(outletPortPath: String): Either[PortPathError, VerifiedOutlet] =
    VerifiedOutlet.find(streamlets, outletPortPath)
}

object VerifiedPortPath {
  def apply(portPath: String): Either[PortPathError, VerifiedPortPath] = {
    val trimmed = portPath.trim()
    val parts   = trimmed.split("\\.").filterNot(_.isEmpty).toVector
    if (trimmed.startsWith(".")) {
      Left(InvalidPortPath(portPath))
    } else if (parts.size == 1 && !portPath.endsWith(".")) {
      Right(VerifiedPortPath(parts.head, None))
    } else if (parts.size >= 2) {
      val portName          = parts.last
      val streamletNamePart = parts.init
      val streamletRef      = streamletNamePart.mkString(".")
      if (streamletRef.isEmpty) Left(InvalidPortPath(portPath))
      else if (portName.isEmpty) Left(InvalidPortPath(portPath))
      else Right(VerifiedPortPath(streamletRef, Some(portName)))
    } else {
      Left(InvalidPortPath(portPath))
    }
  }
}

final case class VerifiedPortPath(streamletRef: String, portName: Option[String]) {
  override def toString = portName.fold(streamletRef)(port ⇒ s"$streamletRef.$port")
}

final case class VerifiedStreamlet(
    name: String,
    descriptor: StreamletDescriptor,
    inletRefs: Vector[InletRef] = Vector.empty[InletRef],
    outletRefs: Vector[OutletRef] = Vector.empty[OutletRef]
) {
  def outlet(outlet: OutletDescriptor) = VerifiedOutlet(this, outlet.name, outlet.schema)
  def inlet(inlet: InletDescriptor)    = VerifiedInlet(this, inlet.name, inlet.schema)
}

final case class VerifiedStreamletConnection(verifiedOutlet: VerifiedOutlet, verifiedInlet: VerifiedInlet, label: Option[String] = None)

sealed trait VerifiedPort {
  def portName: String
  def schemaDescriptor: SchemaDescriptor
}

object VerifiedOutlet {
  def find(
      verifiedStreamlets: Vector[VerifiedStreamlet],
      outletPortPath: String
  ): Either[PortPathError, VerifiedOutlet] =
    for {
      verifiedPortPath ← VerifiedPortPath(outletPortPath)
      verifiedOutlet ← verifiedStreamlets
        .find(_.name == verifiedPortPath.streamletRef)
        .toRight(PortPathNotFound(outletPortPath))
        .flatMap { verifiedStreamlet ⇒
          // a port path can leave out the outlet
          val portNameFound = verifiedPortPath.portName.orElse {
            if (verifiedStreamlet.descriptor.outlets.size == 1) Some(verifiedStreamlet.descriptor.outlets.head.name)
            else None
          }
          verifiedStreamlet.outletRefs
            .find(outletRef => Some(outletRef.outletName) == portNameFound)
            .map { outletRef =>
              Left(PortAlreadyBoundToTopic(outletPortPath, outletRef.topic))
            }
            .getOrElse {
              if (verifiedPortPath.portName.isEmpty && verifiedStreamlet.descriptor.outlets.size > 1) {
                val suggestions = verifiedStreamlet.descriptor.outlets.map(outlet ⇒ verifiedPortPath.copy(portName = Some(outlet.name)))
                Left(PortPathNotFound(outletPortPath, suggestions))
              } else {
                val portPath = if (verifiedPortPath.portName.isEmpty && verifiedStreamlet.descriptor.outlets.size == 1) {
                  verifiedPortPath.copy(portName = Some(verifiedStreamlet.descriptor.outlets.head.name))
                } else verifiedPortPath

                verifiedStreamlet.descriptor.outlets
                  .find(outlet ⇒ Some(outlet.name) == portPath.portName)
                  .map(outletDescriptor ⇒ VerifiedOutlet(verifiedStreamlet, outletDescriptor.name, outletDescriptor.schema))
                  .toRight(PortPathNotFound(outletPortPath))
              }
            }
        }
    } yield verifiedOutlet
}

object VerifiedInlet {
  def find(
      verifiedStreamlets: Vector[VerifiedStreamlet],
      inletPortPath: String
  ): Either[BlueprintProblem, VerifiedInlet] =
    for {
      verifiedPortPath ← VerifiedPortPath(inletPortPath)
      verifiedInlet ← verifiedStreamlets
        .find(_.name == verifiedPortPath.streamletRef)
        .toRight(PortPathNotFound(inletPortPath))
        .flatMap { verifiedStreamlet ⇒
          // a port path can leave out the inlet
          val portNameFound = verifiedPortPath.portName.orElse {
            if (verifiedStreamlet.descriptor.inlets.size == 1) Some(verifiedStreamlet.descriptor.inlets.head.name)
            else None
          }
          verifiedStreamlet.inletRefs
            .find(inletRef => Some(inletRef.inletName) == portNameFound)
            .map { inletRef =>
              Left(PortAlreadyBoundToTopic(inletPortPath, inletRef.topic))
            }
            .getOrElse {
              if (verifiedPortPath.portName.isEmpty && verifiedStreamlet.descriptor.inlets.size > 1) {
                val suggestions = verifiedStreamlet.descriptor.inlets.map(inlet ⇒ verifiedPortPath.copy(portName = Some(inlet.name)))
                Left(PortPathNotFound(inletPortPath, suggestions))
              } else {
                val portPath = if (verifiedPortPath.portName.isEmpty && verifiedStreamlet.descriptor.inlets.size == 1) {
                  verifiedPortPath.copy(portName = Some(verifiedStreamlet.descriptor.inlets.head.name))
                } else verifiedPortPath

                verifiedStreamlet.descriptor.inlets
                  .find(inlet ⇒ Some(inlet.name) == portPath.portName)
                  .map(inletDescriptor ⇒ VerifiedInlet(verifiedStreamlet, inletDescriptor.name, inletDescriptor.schema))
                  .toRight(PortPathNotFound(inletPortPath))
              }
            }
        }
    } yield verifiedInlet
}

final case class VerifiedInlet(streamlet: VerifiedStreamlet, portName: String, schemaDescriptor: SchemaDescriptor) extends VerifiedPort {
  def portPath = VerifiedPortPath(streamlet.name, Some(portName))
}

final case class VerifiedOutlet(streamlet: VerifiedStreamlet, portName: String, schemaDescriptor: SchemaDescriptor) extends VerifiedPort {
  def matches(outletDescriptor: OutletDescriptor) =
    outletDescriptor.name == portName &&
      outletDescriptor.schema.fingerprint == schemaDescriptor.fingerprint
  def portPath = VerifiedPortPath(streamlet.name, Some(portName))
}
