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

package sensordata

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class RotorspeedWindowLogger extends AkkaStreamlet {
  val in    = AvroInlet[Metric]("in")
  val shape = StreamletShape(in)
  override def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = sourceWithCommittableContext(in).via(flow).to(committableSink)
    def flow =
      FlowWithCommittableContext[Metric]
        .grouped(5)
        .map { rotorSpeedWindow ⇒
          val (avg, _) = rotorSpeedWindow.map(_.value).foldLeft((0.0, 1)) { case ((avg, idx), next) ⇒ (avg + (next - avg) / idx, idx + 1) }

          system.log.info(s"Average rotorspeed is: $avg")

          avg
        }
        .mapContext(_.last) // TODO: this is a tricky one to understand...
  }
}
