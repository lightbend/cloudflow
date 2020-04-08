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

package cloudflow.akkastream.scaladsl

import akka.cluster.Cluster
import akka.discovery.Discovery
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.stream.scaladsl.RunnableGraph
import cloudflow.akkastream._
import net.ceedubs.ficus.Ficus._

/**
 * Can be used to define a [[AkkaStreamletLogic]] from a `RunnableGraph[_]`, which will be materialized and instrumented when the [[AkkaStreamlet]] is run.
 */
abstract class RunnableGraphClusterStreamletLogic(implicit context: AkkaStreamletContext) extends AkkaStreamletLogic {

  val cluster = Cluster(system)

  private val localMode = config.as[Option[Boolean]]("cloudflow.local").getOrElse(false)

  if (!localMode) {
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    Discovery(system).loadServiceDiscovery("kubernetes-api")
  }

  /**
   * This method needs to return a `RunnableGraph` that is connected to inlet(s) and/or outlet(s) of the streamlet.
   * See [[AkkaStreamletLogic]] for more information how to create `akka.stream.javadsl.Source`s and `akka.stream.javadsl.Sink`s to inlets and outlets respectively.
   */
  def runnableGraph(): RunnableGraph[_]

  override def run(): Unit = runGraph(runnableGraph())
}
