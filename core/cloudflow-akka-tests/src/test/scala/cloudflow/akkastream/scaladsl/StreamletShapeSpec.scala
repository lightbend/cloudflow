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

package cloudflow.akkastream.scaladsl

import org.scalatest._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream._
import cloudflow.akkastream.testdata._

class StreamletShapeSpec extends WordSpec with MustMatchers {

  "StreamletShape" must {
    "produce a valid shape for 1 inlet and multiple outlets" in {
      object Validation extends AkkaStreamlet {
        val in = AvroInlet[Data]("in")
        val invalid = AvroOutlet[Data]("invalid", _.id.toString)
        val valid = AvroOutlet[Data]("valid", _.id.toString)

        val shape = StreamletShape(in).withOutlets(invalid, valid)
        override def createLogic = null
      }
      import Validation._
      shape.inlets.size mustEqual (1)
      shape.inlets.head.name mustEqual ("in")
      shape.outlets.size mustEqual (2)
      shape.outlets.map(_.name).toSet mustEqual (Set("valid", "invalid"))
    }

    "produce a valid shape for multiple inlets and 1 outlet" in {
      object Validation extends AkkaStreamlet {
        val in0 = AvroInlet[Data]("in-0")
        val in1 = AvroInlet[Data]("in-1")
        val valid = AvroOutlet[Data]("valid", _.id.toString)

        val shape = StreamletShape(valid).withInlets(in0, in1)
        override def createLogic = null
      }
      import Validation._
      shape.inlets.size mustEqual (2)
      shape.inlets.map(_.name).toSet mustEqual (Set("in-0", "in-1"))
      shape.outlets.size mustEqual (1)
      shape.outlets.head.name mustEqual ("valid")
    }

    "produce a valid shape for multiple inlets and multiple outlets" in {
      object Validation extends AkkaStreamlet {
        val in0 = AvroInlet[Data]("in-0")
        val in1 = AvroInlet[Data]("in-1")
        val valid = AvroOutlet[Data]("valid", _.id.toString)
        val invalid = AvroOutlet[Data]("invalid", _.id.toString)

        val shape = StreamletShape.withOutlets(valid, invalid).withInlets(in0, in1)
        override def createLogic = null
      }
      import Validation._
      shape.inlets.size mustEqual (2)
      shape.inlets.map(_.name).toSet mustEqual (Set("in-0", "in-1"))
      shape.outlets.size mustEqual (2)
      shape.outlets.map(_.name).toSet mustEqual (Set("valid", "invalid"))
    }
  }
}

