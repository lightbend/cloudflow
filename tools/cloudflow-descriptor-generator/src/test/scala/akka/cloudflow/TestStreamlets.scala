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

package akka.cloudflow

import com.typesafe.config.Config
import cloudflow.streamlets._

case class Coffee(espressos: Int)
case class Dollars(amount: Double)
case class Gold(kg: Double)
case class Bar(karat: Int)

trait TestStreamlet extends Streamlet {}

abstract class AbstractStreamlet() extends TestStreamlet {}

// ===============================
// Valid Streamlets
// ===============================

class CoffeeIngress extends TestStreamlet {}

class CodeFlow extends TestStreamlet {}

object BarFlow extends TestStreamlet {}

class ClassWithCompanionObject {
  def foo = "bar"
}

object ClassWithCompanionObject extends TestStreamlet {}

class BarFlowWithConfig extends TestStreamlet {
  val GoldPrice = ConfigParameter("gold-price", "the dollar price of gold (1gram)")
  override def configParameters = Vector(GoldPrice)
}

// ===============================
// Invalid Streamlets
// ===============================

class NoDefaultConstructorStreamlet(noDefArgs: String) extends TestStreamlet {
  def useArgs = noDefArgs + noDefArgs
}

class StreamletThatThrowsAnExceptionInItsConstructor extends TestStreamlet {
  throw new RuntimeException("Boom! I will NEVER be instantiated!!")
}
