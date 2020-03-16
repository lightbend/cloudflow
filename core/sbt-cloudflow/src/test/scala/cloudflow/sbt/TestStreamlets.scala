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

import org.apache.avro.{ Schema, SchemaBuilder }

import com.typesafe.config.Config
import cloudflow.streamlets._
import cloudflow.streamlets.avro.AvroUtil

case class Coffee(espressos: Int)
case class Dollars(amount: Double)
case class Gold(kg: Double)
case class Bar(karat: Int)

object Ports {
  def outletFor(schema: Schema) = new Outlet() {
    def name             = "out"
    def schemaDefinition = AvroUtil.createSchemaDefinition(schema)
  }

  def inletFor(schema: Schema) = new Inlet() {
    def name             = "in"
    def schemaDefinition = AvroUtil.createSchemaDefinition(schema)
  }
}

object Schemas {
  val coffeeSchema = SchemaBuilder
    .record("Coffee")
    .namespace("cloudflow.sbt")
    .fields()
    .name("expressos")
    .`type`()
    .nullable()
    .intType()
    .noDefault()
    .endRecord()
  val dollarSchema = SchemaBuilder
    .record("Dollars")
    .namespace("cloudflow.sbt")
    .fields()
    .name("amount")
    .`type`()
    .nullable()
    .doubleType()
    .noDefault()
    .endRecord()
  val barSchema = SchemaBuilder
    .record("Bar")
    .namespace("cloudflow.sbt")
    .fields()
    .name("karat")
    .`type`()
    .nullable()
    .intType()
    .noDefault()
    .endRecord()

}

case object TestRuntime extends StreamletRuntime {
  override val name = "test-runtime"
}

trait TestStreamlet extends Streamlet[StreamletContext] {
  override def createContext(config: Config): StreamletContext    = ???
  override def run(context: StreamletContext): StreamletExecution = ???
  override def runtime: StreamletRuntime                          = TestRuntime
  def logStartRunnerMessage(buildInfo: String): Unit              = ???
}

abstract class AbstractStreamlet() extends TestStreamlet {
  override def shape() = StreamletShape(Ports.inletFor(Schemas.dollarSchema), Ports.outletFor(Schemas.barSchema))
}

// ===============================
// Valid Streamlets
// ===============================

class CoffeeIngress extends TestStreamlet {
  override def shape()     = StreamletShape(Ports.outletFor(Schemas.coffeeSchema))
  override val labels      = Vector("test", "coffee")
  override val description = "Coffee Ingress Test"
}

class CodeFlow extends TestStreamlet {
  override def shape() = StreamletShape(Ports.inletFor(Schemas.coffeeSchema), Ports.outletFor(Schemas.dollarSchema))
}

object BarFlow extends TestStreamlet {
  override def shape() = StreamletShape(Ports.inletFor(Schemas.dollarSchema), Ports.outletFor(Schemas.barSchema))
}

class ClassWithCompanionObject {
  def foo = "bar"
}
object ClassWithCompanionObject extends TestStreamlet {
  override def shape() = StreamletShape(Ports.inletFor(Schemas.dollarSchema), Ports.outletFor(Schemas.barSchema))
}

class BarFlowWithConfig extends TestStreamlet {
  override def shape()          = StreamletShape(Ports.inletFor(Schemas.dollarSchema), Ports.outletFor(Schemas.barSchema))
  val GoldPrice                 = DoubleConfigParameter("gold-price", "the dollar price of gold (1gram)")
  override def configParameters = Vector(GoldPrice)
}

class BarFlowWithJavaConfig extends TestStreamlet {
  override def shape()                = StreamletShape(Ports.inletFor(Schemas.dollarSchema), Ports.outletFor(Schemas.barSchema))
  val GoldPrice                       = DoubleConfigParameter("gold-price", "the dollar price of gold (1gram)")
  override def defineConfigParameters = Array(GoldPrice)
}

// ===============================
// Invalid Streamlets
// ===============================

class NoDefaultConstructorStreamlet(noDefArgs: String) extends TestStreamlet {
  def useArgs = noDefArgs + noDefArgs

  override def shape() = StreamletShape(Ports.inletFor(Schemas.dollarSchema), Ports.outletFor(Schemas.barSchema))
}

class StreamletThatThrowsAnExceptionInItsConstructor extends TestStreamlet {
  throw new RuntimeException("Boom! I will NEVER be instantiated!!")
  override def shape() = StreamletShape(Ports.inletFor(Schemas.coffeeSchema), Ports.outletFor(Schemas.dollarSchema))
}
