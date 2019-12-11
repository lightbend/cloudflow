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

package cloudflow.streamlets

import org.scalatest.matchers
import org.scalatest.MustMatchers._
import org.scalatest.OptionValues._
import spray.json._

object StreamletJsonOps {

  import DescriptorValidations._

  implicit class JsonOps(val json: JsValue) extends AnyVal {

    def arrayElement(field: String): Seq[JsValue] = {
      val JsArray(elements) = json.asJsObject().fields(field)
      elements
    }

    def field(field: String): Option[JsValue] = {
      json.asJsObject().fields.get(field)
    }

    def fields(): Seq[JsValue] = {
      json.asJsObject().fields.values.toSeq
    }

    def stringField(name: String): Option[String] = {
      json.asJsObject().fields.get(name).collect { case JsString(txt) ⇒ txt }
    }

    def mustBeAStreamletPortDescriptorFor(port: StreamletPort): Unit = {
      val jObj = json.asJsObject
      jObj.field("name").value must haveStringValue(port.name)
      val schema = jObj.field("schema").value

      val name = port.schemaDefinition.name
      schema.field("name").value must haveStringValue(name)

      val format = port.schemaDefinition.format
      schema.field("format").value must haveStringValue(format)

      val fls1 = schema.fields.collect { case JsString(s) ⇒ s }.toList
      val fls2 = List(port.schemaDefinition.fingerprint, port.schemaDefinition.format, port.schemaDefinition.name, port.schemaDefinition.schema)
      fls1 must equal(fls2)
    }

    def mustBeAStreamletDescriptorFor(streamlet: Streamlet[StreamletContext]): Unit = {
      val streamletDescriptor = json
      streamletDescriptor.field("runtime").value must haveStringValue(streamlet.runtime.name)
      streamletDescriptor.field("description").value must haveStringValue(streamlet.description)
      streamletDescriptor.arrayElement("labels") must haveStringValues(streamlet.labels)

      def validatePorts(ports: Seq[StreamletPort], fieldName: String): Unit = ports.foreach { port ⇒
        val descriptor = streamletDescriptor.arrayElement(fieldName).findElement("name", port.name).value
        descriptor.mustBeAStreamletPortDescriptorFor(port)
      }

      validatePorts(streamlet.inlets, "inlets")
      validatePorts(streamlet.outlets, "outlets")
    }
  }

  implicit class JsonSeqOps(val jsonSeq: Seq[JsValue]) extends AnyVal {
    def findElement(field: String, value: String): Option[JsValue] = {
      jsonSeq.find(entry ⇒ entry.asJsObject.fields(field) == JsString(value))
    }
  }
}

trait DescriptorValidations {

  import matchers._
  class HasField(name: String) extends Matcher[JsValue] {
    def apply(left: JsValue): MatchResult = {
      val jsObj = left.asJsObject("Expected a JSON Object")
      MatchResult(
        jsObj.fields.contains(name),
        s"""Descriptor did not contain expected field "$name" """,
        s"""Descriptor contains field "$name" """)
    }
  }

  class HasValue(value: JsValue) extends Matcher[JsValue] {
    def apply(left: JsValue): MatchResult = {
      MatchResult(left == value, s"$left did not match expected $value", s"$left matches $value")
    }
  }
  class HasValues(value: Seq[JsValue]) extends Matcher[Seq[JsValue]] {
    def apply(left: Seq[JsValue]): MatchResult = {
      MatchResult(left == value, s"$left did not match expected $value", s"$left matches $value")
    }
  }

  def haveStringValue(value: String): Matcher[JsValue] = new HasValue(JsString(value))

  def haveStringValues(values: Seq[String]): Matcher[Seq[JsValue]] = new HasValues(values.sorted.map(JsString(_)).toVector)

  def haveField(fieldName: String): Matcher[JsValue] = new HasField(fieldName)

}
object DescriptorValidations extends DescriptorValidations
