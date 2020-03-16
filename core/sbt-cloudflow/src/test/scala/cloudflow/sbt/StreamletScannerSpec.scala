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

package cloudflow.sbt

import org.scalatest._
import scala.collection.JavaConverters._
import scala.util.Success

final class StreamletScannerSpec extends WordSpec with TryValues with OptionValues with MustMatchers {

  "StreamletScanner.scan" should {
    val classLoader       = this.getClass.getClassLoader
    val results           = StreamletScanner.scan(classLoader)
    val (valid, invalid)  = results.partition { case (_, triedDiscoveredStreamlet) ⇒ triedDiscoveredStreamlet.isSuccess }
    val validStreamlets   = valid.map { case (k, Success(discovered)) ⇒ (k, discovered) }
    val invalidStreamlets = invalid.toMap

    // These are all valid streamlets defined in TestStreamlets.scala
    "find all valid test streamlets" in {
      valid must have size 6
    }

    // These are all invalid streamlets defined in TestStreamlets.scala
    "find all invalid test streamlets" in {
      invalid must have size 2
    }

    "find streamlets defined as objects" in {
      validStreamlets.keys must contain(BarFlow.getClass.getCanonicalName)
    }

    "find streamlets defined as companion objects" in {
      validStreamlets.keys must contain(ClassWithCompanionObject.getClass.getCanonicalName)
    }

    "find streamlets defined as classes with a default constructor" in {
      validStreamlets.keys must contain(classOf[CodeFlow].getCanonicalName)
    }

    "find streamlet with config parameters" in {
      val key = classOf[BarFlowWithConfig].getCanonicalName
      validStreamlets.keys must contain(key)

      val expected = new BarFlowWithConfig
      validStreamlets(key)
        .getConfigList("config_parameters")
        .asScala
        .map { confParConf ⇒
          confParConf.getString("description") mustBe expected.GoldPrice.description
          confParConf.getString("key") mustBe expected.GoldPrice.key
          confParConf.getString("validation_type") mustBe expected.GoldPrice.toDescriptor.validationType
        }
    }

    "find streamlet with Java config parameters" in {
      val key = classOf[BarFlowWithJavaConfig].getCanonicalName
      validStreamlets.keys must contain(key)

      val expected = new BarFlowWithJavaConfig
      validStreamlets(key)
        .getConfigList("config_parameters")
        .asScala
        .map { confParConf ⇒
          confParConf.getString("description") mustBe expected.GoldPrice.description
          confParConf.getString("key") mustBe expected.GoldPrice.key
          confParConf.getString("validation_type") mustBe expected.GoldPrice.toDescriptor.validationType
        }
    }

    "produce failures for classes with no default constructor" in {
      val noConstructorFailure = invalidStreamlets.get("cloudflow.sbt.NoDefaultConstructorStreamlet").value
      noConstructorFailure.failure.exception mustBe a[ConstructorMissing]
    }

    "produce failures for classes with constructors that throw exceptions" in {
      val noConstructorFailure = invalidStreamlets.get("cloudflow.sbt.StreamletThatThrowsAnExceptionInItsConstructor").value
      noConstructorFailure.failure.exception mustBe a[ConstructorFailure]
    }

    "produce no failures for abstract Streamlet classes" in {
      val abstractClassFailure = invalidStreamlets.get("cloudflow.sbt.AbstractStreamlet")
      abstractClassFailure mustBe None
    }
  }
}
