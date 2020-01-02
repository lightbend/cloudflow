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

import org.scalatest.{ MustMatchers, TryValues, WordSpec }

// Definitions for test purposes
class Foo
class Bar extends Foo

class ClassWithArgsConstructor(arg: Int) extends Bar {
  def args = arg
}
class ClassWithNoArgsConstructor() extends Bar

object BarObject extends Bar

class ClassWithCompanionObject
object ClassWithCompanionObject

class ClassWithArgsAndCompanionObject(arg: Int) {
  def args = arg
}
object ClassWithArgsAndCompanionObject

class ClassOpsSpec extends WordSpec with MustMatchers with TryValues {

  import ClassOps._

  "nameOf" should {
    "return the fully qualified class name of the specified type" in {
      nameOf[Foo] mustBe "cloudflow.streamlets.Foo"
    }
    "return the fully qualified class name of the specified instance" in {
      nameOf(BarObject) mustBe "cloudflow.streamlets.BarObject"
    }
  }

  "instanceOf" should {
    import ClassOps._
    "create a new instance of a class" in {
      instanceOf("cloudflow.streamlets.Bar").success.value mustBe a[Bar]
    }

    "reuse the object instance of a singleton Object" in {
      instanceOf("cloudflow.streamlets.BarObject").success.value mustBe (BarObject)
    }

    "reuse the object instance of a singleton Object with name passed with a $" in {
      instanceOf("cloudflow.streamlets.BarObject$").success.value mustBe (BarObject)
    }

    "fail to create an instance for a class without a no-args constructor" in {
      instanceOf("cloudflow.streamlets.ClassWithArgsConstructor").failure.exception mustBe a[InstantiationException]
    }

    "create a new instance of a class with a no-arg constructor and a companion object" in {
      instanceOf("cloudflow.streamlets.ClassWithCompanionObject").success.value mustBe a[ClassWithCompanionObject]
    }

    "reuse the object instance when we have a class with no no-arg constructor along with a companion Object" in {
      instanceOf("cloudflow.streamlets.ClassWithArgsAndCompanionObject").success.value mustBe (ClassWithArgsAndCompanionObject)
    }
  }
}
