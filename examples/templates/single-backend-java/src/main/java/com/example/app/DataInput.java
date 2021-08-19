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

// required to work with the streamlet API
import akka.stream.javadsl.RunnableGraph;
import akka.stream.javadsl.Source;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;

// classes used in this particular example
import akka.NotUsed;
import scala.Some;

import java.time.Duration;
import java.util.Random;
import scala.concurrent.duration.*;

// pick the streamlet implementation corresponding to your chosen backend
// import cloudflow.spark._
// import cloudflow.flink._
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;

// Implement the streamlet extending the corresponding base to the chosen backend:
// AkkaStreamlet, SparkSteamlet, FlinkStreamlet
public class DataInput extends AkkaStreamlet {

  // declare inputs and outputs
  // outputs may declare a partitioner
  AvroOutlet<Data> out = AvroOutlet.<Data>create("out", Data.class);

  // declare the 'shape' of the streamlet with the input and the outputs
  @Override
  public StreamletShape shape() {
    return StreamletShape.createWithOutlets(out);
  }

  // Streamlet may have configuration parameters
  IntegerConfigParameter rateConf = new IntegerConfigParameter("rate", "The rate of record generation per second.", Some.apply(50));

  // If we declare (optional) config parameters, we must override `configParameters` with a sequence of the declared
  // configurations
  @Override
  public ConfigParameter[] defineConfigParameters() {
    return new ConfigParameter[]{rateConf};
  }

  // in `createLogic` we implement the business logic of this Streamlet
  @Override
  public RunnableGraphStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {
      int rate = rateConf.getValue(getContext());
      Random rng = new Random();
      @Override
      public RunnableGraph<NotUsed> createRunnableGraph() {
       return Source.repeat(0).map(elem -> new Data("id-" + rng.nextInt(9999), rng.nextDouble()))
         .throttle(rate, Duration.ofSeconds(1))
         .to(getPlainSink(out));
      }
    };
  }

}
