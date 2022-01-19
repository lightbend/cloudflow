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
package com.example.app;

// general imports to work with Akka-streams
import akka.NotUsed;
import akka.stream.*;
import akka.stream.javadsl.*;

// pick the streamlet implementation corresponding to your chosen backend
// import cloudflow.spark._
// import cloudflow.flink._

import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;


// Implement the streamlet extending the corresponding base to the chosen backend:
// AkkaStreamlet, SparkSteamlet, FlinkStreamlet
public class ConsoleOutput extends AkkaStreamlet {

  // Create inputs and outputs by declaring inlets and outlets
  AvroInlet<Data> inlet = AvroInlet.<Data>create("in", Data.class);

  // Define the shape of the streamlet
  public StreamletShape shape() {
    return StreamletShape.createWithInlets(inlet);
  }

  // for akka-streams streamlets, the entry point of the logic is the runnableGraph.
  // check the Streamlet API of your chosen implementation to determine the entry point
  // corresponding to your chosen backend.
  public RunnableGraphStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {

      public String format(Data data) {
        return data.getId() + "-> " +data.getValue();
      }
      public RunnableGraph<NotUsed> createRunnableGraph() {
        return getPlainSource(inlet)
        .to(Sink.foreach(data -> System.out.println(format(data))));

      }
    };
  }
}
