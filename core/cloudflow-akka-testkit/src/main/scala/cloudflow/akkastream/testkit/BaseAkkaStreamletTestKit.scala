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

package cloudflow.akkastream.testkit

import scala.annotation.varargs
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor._
import akka.stream._
import com.typesafe.config._

import cloudflow.akkastream._
import cloudflow.streamlets._

// The type parameter trick used below is for util methods in this base class to produce
// correctly typed instances of the two sub-classes, e.g. when calling `withConfig`
// on the javadsl version you will get the javadsl subclass back instead of a
// reference to this private base class.
// Thanks to @debasish for the tip about F-bounded types in combination with self-types.
private[testkit] abstract class BaseAkkaStreamletTestKit[Repr <: BaseAkkaStreamletTestKit[Repr]] { this: Repr ⇒
  def system: ActorSystem
  def mat: Option[ActorMaterializer]
  def config: Config

  private val defaultStreamletRefName = "streamlet-under-test"

  /**
   * Returns an instance of this Testkit with the specified configuration loaded
   * into the Akka `ActorSystem`.
   */
  def withConfig(c: Config): Repr

  /**
   * Adds configuration parameters and their values to the configuration used in the test.
   *
   * [[ConfigParameterValue]] takes a [[cloudflow.streamlets.ConfigParameter ConfigParameter]] and a string containing the value of the parameter.
   */
  @varargs
  def withConfigParameterValues(configParameterValues: ConfigParameterValue*): Repr = {
    val parameterValueConfig =
      ConfigFactory.parseString(
        configParameterValues
          .map(parameterValue ⇒
            s"cloudflow.streamlets.$defaultStreamletRefName.${parameterValue.configParameterKey} = ${parameterValue.value}"
          )
          .mkString("\n")
      )

    withConfig(config.withFallback(parameterValueConfig).resolve)
  }

  /**
   * Runs the `streamlet` using `ip` as the source and `op` as the sink. After running the streamlet it also
   * runs the assertions.
   */
  def run[T](streamlet: AkkaStreamlet, ip: InletTap[_], op: OutletTap[T], assertions: () ⇒ Any): Unit =
    doRun(TestContext(defaultStreamletRefName, system, List(ip), List(op), config), streamlet, assertions)

  /**
   * Runs the `streamlet` using an empty source and `op` as the sink. After running the streamlet it also
   * runs the assertions.
   */
  def run[T](streamlet: AkkaStreamlet, op: OutletTap[T], assertions: () ⇒ Any): Unit =
    doRun(TestContext(defaultStreamletRefName, system, List.empty, List(op), config), streamlet, assertions)

  /**
   * Runs the `streamlet` using `ip` as the source and an empty sink. After running the streamlet it also
   * runs the assertions.
   */
  def run[T](streamlet: AkkaStreamlet, ip: InletTap[T], assertions: () ⇒ Any): Unit =
    doRun(TestContext(defaultStreamletRefName, system, List(ip), List.empty, config), streamlet, assertions)

  /**
   * Runs the `streamlet` using a list of `ip` as the source and a list of `op` as the sink. After running the streamlet it also
   * runs the assertions.
   */
  def run[T](streamlet: AkkaStreamlet, ip: List[InletTap[_]], op: List[OutletTap[_]], assertions: () ⇒ Any): Unit =
    doRun(TestContext(defaultStreamletRefName, system, ip, op, config), streamlet, assertions)

  /**
   * Runs the `streamlet` using a list of `ip` as the source and an `op` as the sink. After running the streamlet it also
   * runs the assertions.
   */
  def run[T](streamlet: AkkaStreamlet, ip: List[InletTap[_]], op: OutletTap[T], assertions: () ⇒ Any): Unit =
    doRun(TestContext(defaultStreamletRefName, system, ip, List(op), config), streamlet, assertions)

  /**
   * Runs the `streamlet` using an `ip` as the source and a list of `op` as the sink. After running the streamlet it also
   * runs the assertions.
   */
  def run[T](streamlet: AkkaStreamlet, ip: InletTap[_], op: List[OutletTap[_]], assertions: () ⇒ Any): Unit =
    doRun(TestContext(defaultStreamletRefName, system, List(ip), op, config), streamlet, assertions)

  /**
   * This method is used when `testkit.run` and `StreamletExecution#stop` has to be
   * done under different control flows.
   */
  def run[T](streamlet: AkkaStreamlet, ip: List[InletTap[_]], op: List[OutletTap[_]]): StreamletExecution = {
    val context = TestContext(defaultStreamletRefName, system, ip, op, config)
    streamlet.setContext(context).run(context.config)
  }

  private def doRun(context: TestContext, streamlet: AkkaStreamlet, assertions: () ⇒ Any): Unit = {
    val streamletExecution = streamlet.setContext(context).run(context.config)
    val _                  = assertions()
    Await.result(streamletExecution.stop(), 10 seconds)
  }
}
