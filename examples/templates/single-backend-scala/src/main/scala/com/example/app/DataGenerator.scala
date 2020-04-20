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

// required to work with the streamlet API
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

// classes used in this particular example
import akka.NotUsed
import akka.stream.scaladsl._
import scala.util.Random
import scala.concurrent.duration._

// pick the streamlet implementation corresponding to your chosen backend
// import cloudflow.spark._
// import cloudflow.flink._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._

// Implement the streamlet extending the corresponding base to the chosen backend:
// AkkaStreamlet, SparkSteamlet, FlinkStreamlet
class DataInput extends AkkaStreamlet {

  // declare inputs and outputs
  // outputs may declare a partitioner
  val out = AvroOutlet[Data]("out").withPartitioner(RoundRobinPartitioner)

  // declare the 'shape' of the streamlet with the input and the outputs
  val shape = StreamletShape(out)

  // Streamlet may have configuration parameters
  val RateConf = IntegerConfigParameter("rate", "The rate of record generation per second.", Some(50))

  // If we declare (optional) config parameters, we must override `configParameters` with a sequence of the declared
  // configurations
  override def configParameters = Vector(RateConf)

  // in `createLogic` we implement the business logic of this Streamlet
  override def createLogic() = new RunnableGraphStreamletLogic() {
    val rate = getStreamletConfig.getInt(RateConf.key)
    println(s"Producing elements at $rate/s")
    def runnableGraph = {
      val source = Source.repeat(NotUsed).map(_ => Data("id-" + Random.nextInt(9999), Random.nextDouble))
      source
        .throttle(rate, 1.seconds)
        .to(plainSink(out))
    }
  }

}
