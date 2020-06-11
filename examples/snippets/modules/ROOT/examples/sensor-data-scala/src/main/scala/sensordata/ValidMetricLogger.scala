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
//tag::all[]
package sensordata

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

//tag::config-parameter1[]
class ValidMetricLogger extends AkkaStreamlet {
//end::config-parameter1[]

  val inlet = AvroInlet[Metric]("in")
  val shape = StreamletShape.withInlets(inlet)

  val LogLevel = RegExpConfigParameter(
    "log-level",
    "Provide one of the following log levels, debug, info, warning or error",
    "^debug|info|warning|error$",
    Some("debug")
  )

  //tag::config-parameter2[]
  val MsgPrefix = StringConfigParameter("msg-prefix", "Provide a prefix for the log lines", Some("valid-logger"))
  //end::config-parameter2[]

  override def configParameters = Vector(LogLevel, MsgPrefix)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val logF: String ⇒ Unit = LogLevel.value.toLowerCase match {
      case "debug"   ⇒ system.log.debug _
      case "info"    ⇒ system.log.info _
      case "warning" ⇒ system.log.warning _
      case "error"   ⇒ system.log.error _
    }

    val msgPrefix = MsgPrefix.value

    def log(metric: Metric) =
      logF(s"$msgPrefix $metric")

    def flow =
      FlowWithCommittableContext[Metric]
        .map { validMetric ⇒
          log(validMetric)
          validMetric
        }

    def runnableGraph =
      sourceWithCommittableContext(inlet).via(flow).to(committableSink)
  }
}
//end::all[]
