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

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastream.util.scaladsl.Merger

class SensorDataMerge extends AkkaStreamlet {
  val in0 = AvroInlet[SensorData]("in-0")
  val in1 = AvroInlet[SensorData]("in-1")
  val out = AvroOutlet[SensorData]("out", _.deviceId.toString)

  final override val shape = StreamletShape.withInlets(in0, in1).withOutlets(out)
  final override def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = Merger.source(Vector(in0, in1)).to(committableSink(out))
  }

}
