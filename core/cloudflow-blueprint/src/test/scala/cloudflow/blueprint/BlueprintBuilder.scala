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

/**
 * Builds [[Blueprint]]s and [[VerifiedBlueprint]]s for testing purposes.
 * See [[BlueprintSpec]] for how the builder can be used.
 */
object BlueprintBuilder extends StreamletDescriptorBuilder {

  /**
   * Creates an unconnected blueprint.
   * Defines the given streamlets to be used in the blueprint, adds / uses a randomly named reference for every provided streamlet.
   */
  def unconnectedBlueprint(streamletDescriptors: StreamletDescriptor*): Blueprint = {
    val blueprint   = Blueprint()
    val descriptors = streamletDescriptors.toVector
    // find outlets and inlets of shared schema.
    val defined = blueprint
      .define(descriptors)
    val refsAdded = descriptors.foldLeft(defined) { (bp, descriptor) ⇒
      bp.use(descriptor.randomRef)
    }
    refsAdded.verify
  }

  /**
   * Creates a connected blueprint in the order that the streamlet descriptors are specified.
   * Defines the given streamlet descriptors to be used in the blueprint, adds / uses a randomly named reference for every provided streamlet descriptor,
   * connects every streamlets one by one.
   * Connections are made to all inlets of the next streamlet that match the outlet schema of the previous streamlet.
   */
  def connectedBlueprint(streamletDescriptors: StreamletDescriptor*): Blueprint = {
    val refsAdded = unconnectedBlueprint(streamletDescriptors: _*)
    val connected = streamletDescriptors.sliding(2, 1).foldLeft(refsAdded) { (bp, pair) ⇒
      val out    = pair.head
      val in     = pair.last
      val outRef = refsAdded.streamlets.find(_.className == out.className).get
      val inRef  = refsAdded.streamlets.find(_.className == in.className).get

      out.outlets
        .map { outlet ⇒
          Topic(
            s"${outRef.name}.${outlet.name}",
            in.inlets
              .filter(_.schema == outlet.schema)
              .map { inlet ⇒
                s"${inRef.name}.${inlet.name}"
              }
              .toVector :+ s"${outRef.name}.${outlet.name}"
          )
        }
        .foldLeft(bp) { (connectingBlueprint, topic) ⇒
          connectingBlueprint.connect(topic, topic.connections)
        }
    }

    val connectedInlets = connected.verify

    // generate topic named exactly as portPath connect to portPath of unconnected outlets.
    connectedInlets.problems
      .collect {
        case UnconnectedOutlets(unconnectedPorts) => unconnectedPorts
      }
      .flatten
      .foldLeft(connectedInlets) { (connectingBlueprint, unconnectedPort) =>
        val portPath = s"${unconnectedPort.streamletRef}.${unconnectedPort.port.name}"
        val topic    = Topic(portPath)
        connectingBlueprint.connect(topic, portPath)
      }
      .verify
  }

  /**
   * Forces verification of a blueprint. Fails with a scalatest value if the blueprint is not valid.
   */
  def verified(blueprint: Blueprint): VerifiedBlueprint =
    blueprint.verified.right.value

  /**
   * Creates a connected [[VerifiedBlueprint]], see [[connectedBlueprint]].
   */
  def verifiedConnectedBlueprint(streamletDescriptors: StreamletDescriptor*): VerifiedBlueprint =
    connectedBlueprint(streamletDescriptors: _*).verified.right.value

  /**
   * Adds methods to [[StreamletRef]] for ease of testing.
   * The methods here make it possible to write:
   * - `streamletRef.in`
   * - `streamletRef.out`
   * - `streamletRef.in0`
   * - `streamletRef.in1`
   * as a path to the inlet / outlet which can be used for connecting streamlets instead of manually constructing strings.
   */
  implicit class StreamletRefOps(streamletRef: StreamletRef) {
    def inlet(name: String)  = s"${streamletRef.name}.$name"
    def outlet(name: String) = s"${streamletRef.name}.$name"
    def in                   = s"${streamletRef.name}.in"
    def out                  = s"${streamletRef.name}.out"
    def in0                  = s"${streamletRef.name}.in-0"
    def in1                  = s"${streamletRef.name}.in-1"
  }
}
