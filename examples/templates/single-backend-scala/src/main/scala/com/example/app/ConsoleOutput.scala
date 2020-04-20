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
package com.example.app

// pick the streamlet implementation corresponding to your chosen backend
// import cloudflow.spark._
// import cloudflow.flink._

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

// Implement the streamlet extending the corresponding base to the chosen backend:
// AkkaStreamlet, SparkSteamlet, FlinkStreamlet
class ConsoleOutput extends AkkaStreamlet {

  // declare inputs, outputs, and a shape
  val inlet = AvroInlet[Data]("in")
  val shape = StreamletShape.withInlets(inlet)

  // in `createLogic` we implement the business logic of this Streamlet
  override def createLogic = new RunnableGraphStreamletLogic() {
    // for akka-streams streamlets, the entry point of the logic is the runnableGraph.
    // check the Streamlet API of your chosen implementation to determine the entry point
    // corresponding to your chosen backend.
    def runnableGraph =
      sourceWithOffsetContext(inlet).via(flow).to(committableSink)

    // flow is a help function to make the structure more readable
    val flow = FlowWithCommittableContext[Data]
      .map { data ⇒
        println(data)
        data
      }
  }
}
