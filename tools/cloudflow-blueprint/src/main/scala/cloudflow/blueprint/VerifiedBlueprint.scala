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

import com.typesafe.config.Config

case class VerifiedBlueprint(streamlets: Vector[VerifiedStreamlet], topics: Vector[VerifiedTopic])

object VerifiedPortPath {
  def apply(portPath: String): Either[PortPathError, VerifiedPortPath] = {
    val trimmed = portPath.trim()
    val parts   = trimmed.split("\\.").filterNot(_.isEmpty).toVector
    if (trimmed.startsWith(".")) {
      Left(InvalidPortPath(portPath))
    }
    //
    // else if (parts.size == 1 && !portPath.endsWith(".")) {
    //   Right(VerifiedPortPath(parts.head, None))
    // }
    else if (parts.size >= 2) {
      val portName          = parts.last
      val streamletNamePart = parts.init
      val streamletRef      = streamletNamePart.mkString(".")
      if (streamletRef.isEmpty) Left(InvalidPortPath(portPath))
      else if (portName.isEmpty) Left(InvalidPortPath(portPath))
      else Right(VerifiedPortPath(streamletRef, portName))
    } else {
      Left(InvalidPortPath(portPath))
    }
  }
}

final case class VerifiedPortPath(streamletRef: String, portName: String) {
  override def toString = s"$streamletRef.$portName"
}

final case class VerifiedStreamlet(name: String, descriptor: StreamletDescriptor) {
  def outlet(outlet: OutletDescriptor) = VerifiedOutlet(this, outlet.name, outlet.schema)
  def inlet(inlet: InletDescriptor)    = VerifiedInlet(this, inlet.name, inlet.schema)
}

final case class VerifiedTopic(id: String, connections: Vector[VerifiedPort], cluster: Option[String], kafkaConfig: Config)
final case class VerifiedStreamletConnection(verifiedOutlet: VerifiedOutlet, verifiedInlet: VerifiedInlet, label: Option[String] = None)

sealed trait VerifiedPort {
  def streamlet: VerifiedStreamlet
  def portName: String
  def schemaDescriptor: SchemaDescriptor
  def portPath: VerifiedPortPath
  def isOutlet: Boolean
}

object VerifiedPort {
  def findPort(verifiedPortPath: VerifiedPortPath, verifiedStreamlets: Vector[VerifiedStreamlet]): Either[PortPathError, VerifiedPort] = {
    val portPath = verifiedPortPath.toString
    verifiedStreamlets
      .find(_.name == verifiedPortPath.streamletRef)
      .toRight(PortPathNotFound(portPath))
      .flatMap { verifiedStreamlet =>
        val ports = verifiedStreamlet.descriptor.outlets ++ verifiedStreamlet.descriptor.inlets

        ports
          .find(port => port.name == verifiedPortPath.portName)
          .map { portDescriptor =>
            if (verifiedStreamlet.descriptor.outlets.exists(_.name == portDescriptor.name))
              VerifiedOutlet(verifiedStreamlet, portDescriptor.name, portDescriptor.schema)
            else VerifiedInlet(verifiedStreamlet, portDescriptor.name, portDescriptor.schema)
          }
          .toRight(PortPathNotFound(portPath))
      }
  }

  def collectPorts(verifiedPortPaths: Vector[VerifiedPortPath],
                   verifiedStreamlets: Vector[VerifiedStreamlet]): Either[Vector[PortPathError], Vector[VerifiedPort]] = {
    val results: Vector[Either[PortPathError, VerifiedPort]] = verifiedPortPaths.map { verifiedPortPath =>
      VerifiedPort.findPort(verifiedPortPath, verifiedStreamlets)
    }
    results.partition(_.isLeft) match {
      case (errors, ports) if errors.isEmpty => Right((for (Right(p) <- ports) yield p).toVector)
      case (errors, _)                       => Left((for (Left(e)   <- errors) yield e).toVector)
    }
  }
}

final case class VerifiedInlet(streamlet: VerifiedStreamlet, portName: String, schemaDescriptor: SchemaDescriptor) extends VerifiedPort {
  def portPath = VerifiedPortPath(streamlet.name, portName)
  def isOutlet = false
}

final case class VerifiedOutlet(streamlet: VerifiedStreamlet, portName: String, schemaDescriptor: SchemaDescriptor) extends VerifiedPort {
  def matches(outletDescriptor: OutletDescriptor) =
    outletDescriptor.name == portName &&
      outletDescriptor.schema.fingerprint == schemaDescriptor.fingerprint
  def portPath = VerifiedPortPath(streamlet.name, portName)
  def isOutlet = true
}
