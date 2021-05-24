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

package taxiride.logger

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import taxiride.datamodel._

class FarePerRideLogger extends AkkaStreamlet {
  val inlet = AvroInlet[TaxiRideFare]("in")
  val shape = StreamletShape.withInlets(inlet)

  val LogLevel = RegExpConfigParameter(
    "log-level",
    "Provide one of the following log levels, debug, info, warning or error",
    "^debug|info|warning|error$",
    Some("info")
  )

  val MsgPrefix = StringConfigParameter("msg-prefix", "Provide a prefix for the log lines", Some("valid-logger"))

  override def configParameters = Vector(LogLevel, MsgPrefix)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val logF: String ⇒ Unit = LogLevel.value.toLowerCase match {
      case "debug"   ⇒ system.log.debug _
      case "info"    ⇒ system.log.info _
      case "warning" ⇒ system.log.warning _
      case "error"   ⇒ system.log.error _
    }

    val msgPrefix = MsgPrefix.value

    def log(rideFare: TaxiRideFare) =
      logF(s"$msgPrefix $rideFare")

    def flow =
      FlowWithCommittableContext[TaxiRideFare]
        .map { taxiRideFare ⇒
          log(taxiRideFare)
          taxiRideFare
        }

    def runnableGraph =
      sourceWithCommittableContext(inlet)
        .via(flow)
        .to(committableSink)
  }
}
