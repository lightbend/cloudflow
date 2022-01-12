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

package cloudflow.streamlets

import scala.collection.immutable

import com.typesafe.config.Config
import org.slf4j.LoggerFactory

import cloudflow.streamlets.descriptors.StreamletDescriptor

abstract class Streamlet[Context <: StreamletContext] {
  @transient protected lazy val log = LoggerFactory.getLogger(getClass.getName)

  @transient @volatile private var ctx: Context = _

  /**
   * Returns the [[StreamletContext]] in which this streamlet is run. It can only be accessed when the streamlet is run.
   */
  protected final implicit def context: Context = {
    if (ctx == null)
      throw new StreamletContextException("StreamletContext can only be accessed from the `createLogic()` method.")
    ctx
  }

  /**
   * Java API
   *
   * Returns the [[StreamletContext]] in which this streamlet is run. It can only be accessed when the streamlet is run.
   */
  protected final def getContext(): Context = context

  /**
   * This method is used to inject a `StreamletContext` directly instead of through the
   * `Config`. This is used mainly by the testkit to inject the test context.
   */
  private[cloudflow] def setContext(context: Context): Streamlet[Context] = {
    ctx = context
    this
  }

  def runtime: StreamletRuntime

  def shape(): StreamletShape

  final def inlets: immutable.IndexedSeq[Inlet] = shape.inlets
  final def outlets: immutable.IndexedSeq[Outlet] = shape.outlets

  def labels: immutable.IndexedSeq[String] = Vector.empty
  def description: String = ""

  /**
   * Defines a set of configuration parameters that will be used in this streamlet to lookup
   * environment-specific configuration to be provided at deployment time.
   */
  def configParameters: immutable.IndexedSeq[ConfigParameter] = immutable.IndexedSeq.empty ++ defineConfigParameters()

  /**
   * Java API
   *
   * Defines configuration parameters that will be used in this streamlet to lookup
   * environment-specific configuration to be provided at deployment time.
   */
  def defineConfigParameters(): Array[ConfigParameter] = Array[ConfigParameter]()

  /**
   * A set of custom attributes that a streamlet can use to activate features in the
   * Cloudflow runtime.
   */
  protected[cloudflow] def attributes: immutable.Set[StreamletAttribute] = customAttributes ++ defineCustomAttributes()

  /**
   * A set of custom attributes that a streamlet can use to activate features in the
   * Cloudflow runtime.
   */
  def customAttributes: immutable.Set[StreamletAttribute] = immutable.Set.empty

  /**
   * Java API
   *
   * Defines a set of custom attributes that a streamlet can use to activate features in the
   * Cloudflow runtime.
   */
  def defineCustomAttributes(): Array[StreamletAttribute] = Array[StreamletAttribute]()

  /**
   * Runs the streamlet. Called by the cloudflow.runner.Runner.
   */
  private[cloudflow] final def run(config: Config): StreamletExecution = {
    this.synchronized {
      if (ctx == null) ctx = createContext(config)
    }
    run(ctx)
  }

  /**
   * Runs the streamlet.
   */
  def run(context: Context): StreamletExecution

  /**
   * Creates a `StreamletContext` for the appropriate runtime
   */
  protected def createContext(config: Config): Context

  def logStartRunnerMessage(buildInfo: String): Unit

  /**
   * Defines volume mounts that can be used by the streamlet to mount a volume in a local path.
   */
  def volumeMounts: immutable.IndexedSeq[VolumeMount] = defineVolumeMounts().toVector

  /**
   * Java API
   * Defines volume mounts that can be used by the streamlet to mount a volume in a local path.
   */
  def defineVolumeMounts(): Array[VolumeMount] = Array[VolumeMount]()

  /**
   * JSON-Encoded String representing the descriptor of this streamlet.
   */ // FIXME: replace with `def descriptor: Config`
  final def jsonDescriptor: String = StreamletDescriptor.jsonDescriptor(this)
}

/**
 * A simple marker trait to provide the name of the "runtime" supported by
 * a streamlet, e.g. "akka", "spark", etc.
 *
 * Implementations will usually be provided by a runtime support library
 */
trait StreamletRuntime {
  def name: String
}

/**
 * An exception to return when the runner returns an accumulated list of distinct
 * exceptions.
 */
final case class ExceptionAcc(exceptions: Vector[Throwable])
    extends Exception("Exceptions caught: " + exceptions.map(_.getMessage).mkString(","))
