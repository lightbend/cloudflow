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

package cloudflow.spark

import org.apache.avro.SchemaBuilder

import cloudflow.streamlets.{ Inlet, Outlet, SchemaDefinition, StreamletShape }
import cloudflow.streamlets.avro.AvroUtil

case class TestData(name: String, description: String)
case class TestResult(result: String)
case class TestInlet(name: String, schemaDefinition: SchemaDefinition)  extends Inlet
case class TestOutlet(name: String, schemaDefinition: SchemaDefinition) extends Outlet

trait TrivialSparklet extends SparkStreamlet {

  val inSchema = SchemaBuilder
    .record("Input")
    .namespace("cloudflow.runner.spark")
    .fields()
    .name("value")
    .`type`()
    .nullable()
    .intType()
    .noDefault()
    .endRecord()
  val outSchema = SchemaBuilder
    .record("Output")
    .namespace("cloudflow.runner.spark")
    .fields()
    .name("value")
    .`type`()
    .nullable()
    .intType()
    .noDefault()
    .endRecord()

  override def shape() = StreamletShape(
    TestInlet("in", AvroUtil.createSchemaDefinition(inSchema)),
    TestOutlet("out", AvroUtil.createSchemaDefinition(outSchema))
  )

  override def createLogic(): SparkStreamletLogic = new SparkStreamletLogic {
    override def buildStreamingQueries = StreamletQueryExecution(Nil)
  }
}

object ToUpperObject extends TrivialSparklet

class ToUpperClass extends TrivialSparklet

class ToUpperCaseParamClass(param: String) extends TrivialSparklet {
  val _ = param
}

// A Spark Streamlet as a class with an associated companion object
// This Spark Streamlet should load
class SparkStreamletWithCompanionObject extends TrivialSparklet {
  import SparkStreamletWithCompanionObject._
  val x: Int = bar.length
}

object SparkStreamletWithCompanionObject {
  def bar: String = "spark"
}

// A Spark Streamlet as a class with NO no-arg constructor with an associated companion object
// This Spark Streamlet should NOT load
class SparkStreamletWithArgsAndCompanionObject(arg: Int) extends TrivialSparklet {
  import SparkStreamletWithArgsAndCompanionObject._
  def args   = arg
  val x: Int = bar.length
}

object SparkStreamletWithArgsAndCompanionObject {
  def bar: String = "spark"
}

// A Spark Streamlet as a companion object with an associated class
// This Spark Streamlet should load
class SparkStreamletAsCompanionObject(arg: Int) {
  import SparkStreamletWithArgsAndCompanionObject._
  def args   = arg
  val x: Int = bar.length
}

object SparkStreamletAsCompanionObject extends TrivialSparklet {
  def bar: String = "spark"
}

// trait that extends a class that extends SparkStreamlet
// this Spark Streamlet should not load
class ASparkStreamlet extends SparkStreamlet {

  val inSchema = SchemaBuilder
    .record("Input")
    .namespace("cloudflow.runner.spark")
    .fields()
    .name("value")
    .`type`()
    .nullable()
    .intType()
    .noDefault()
    .endRecord()
  val outSchema = SchemaBuilder
    .record("Output")
    .namespace("cloudflow.runner.spark")
    .fields()
    .name("value")
    .`type`()
    .nullable()
    .intType()
    .noDefault()
    .endRecord()

  override def shape() = StreamletShape(
    TestInlet("in", AvroUtil.createSchemaDefinition(inSchema)),
    TestOutlet("out", AvroUtil.createSchemaDefinition(outSchema))
  )

  override def createLogic(): SparkStreamletLogic = new SparkStreamletLogic {
    override def buildStreamingQueries = StreamletQueryExecution(Nil)
  }
}
trait MySparkStreamlet extends ASparkStreamlet
