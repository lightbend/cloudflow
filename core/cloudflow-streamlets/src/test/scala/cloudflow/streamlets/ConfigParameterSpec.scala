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

package cloudflow.streamlets

import cloudflow.streamlets.descriptors._

import org.scalatest._

import com.typesafe.config._

class ConfigParameterSpec extends WordSpec with MustMatchers with OptionValues {

  "ConfigParameterSpec" should {

    "define a parameter and find its key in the configuration" in {
      val streamletConfiguration = """
        | records-in-window = 10
        """.stripMargin
      val config = ConfigFactory.parseString(streamletConfiguration)

      val recordsInWindowParameter = IntegerConfigParameter("records-in-window", "This value describes how many records of data should be processed together, default 64", Some(64))
      config.getInt(recordsInWindowParameter.key) mustBe 10
    }

    "define a custom parameter and find its key in the configuration" in {
      val streamletConfiguration = """
        | time-in-day = "20:30"
        """.stripMargin
      val config = ConfigFactory.parseString(streamletConfiguration)

      case class MilitaryTimeConfigParameter(key: String, defaultValue: Option[String] = None) extends ConfigParameter {
        val description: String = "This parameter validates that the users enter the time in 24h format."
        val validation = RegexpValidationType("^(0[0-9]|1[0-9]|2[0-3]|[0-9]):[0-5][0-9]$")
        def toDescriptor = ConfigParameterDescriptor(key, description, validation, defaultValue)
      }
      val timeInDayRequirement = MilitaryTimeConfigParameter("time-in-day")
      config.getString(timeInDayRequirement.key) mustBe "20:30"
    }

    "validate that IntegerConfigParameter produce a correct descriptor" in {
      val aInt = IntegerConfigParameter("test", "description", Some(20))

      val descriptor = aInt.toDescriptor

      descriptor.key mustBe "test"
      descriptor.description mustBe "description"
      descriptor.validationType mustBe "int32"
      descriptor.validationPattern mustBe empty
      descriptor.defaultValue.value mustBe "20"
    }

    "validate that StringConfigParameter produce a correct descriptor" in {
      val aString = StringConfigParameter("test", "description", Some("test string"))

      val descriptor = aString.toDescriptor

      descriptor.key mustBe "test"
      descriptor.description mustBe "description"
      descriptor.validationType mustBe "string"
      descriptor.validationPattern.value mustBe "^.{1,1000}$"
      descriptor.defaultValue.value mustBe "test string"
    }

    "validate that DoubleConfigParameter produce a correct descriptor" in {
      val aDouble = DoubleConfigParameter("test", "description", Some(3.14))

      val descriptor = aDouble.toDescriptor

      descriptor.key mustBe "test"
      descriptor.description mustBe "description"
      descriptor.validationType mustBe "double"
      descriptor.validationPattern mustBe empty
      descriptor.defaultValue.value mustBe "3.14"
    }

    "validate that BooleanConfigParameter produce a correct descriptor" in {
      val aBool = BooleanConfigParameter("test", "description", Some(true))

      val descriptor = aBool.toDescriptor

      descriptor.key mustBe "test"
      descriptor.description mustBe "description"
      descriptor.validationType mustBe "bool"
      descriptor.validationPattern mustBe empty
      descriptor.defaultValue.value mustBe "true"
    }

    "validate that RegExpConfigParameter produce a correct descriptor" in {
      val aBool = RegExpConfigParameter("test", "description", "^.{1,1000}$", Some("some string"))

      val descriptor = aBool.toDescriptor

      descriptor.key mustBe "test"
      descriptor.description mustBe "description"
      descriptor.validationType mustBe "string"
      descriptor.validationPattern.value mustBe "^.{1,1000}$"
      descriptor.defaultValue.value mustBe "some string"
    }

  }
}
