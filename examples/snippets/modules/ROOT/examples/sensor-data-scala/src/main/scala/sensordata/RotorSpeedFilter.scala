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

package sensordata

import akka.stream.scaladsl.RunnableGraph
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class RotorSpeedFilter extends AkkaStreamlet {
  val in: CodecInlet[Metric]         = AvroInlet[Metric]("in")
  val out: CodecOutlet[Metric]       = AvroOutlet[Metric]("out").withPartitioner(RoundRobinPartitioner)
  override val shape: StreamletShape = StreamletShape(in, out)

  override def createLogic(): AkkaStreamletLogic = new RunnableGraphStreamletLogic() {
    def flow = FlowWithCommittableContext[Metric]().filter(_.name == "rotorSpeed")

    override def runnableGraph(): RunnableGraph[_] = sourceWithCommittableContext(in).via(flow).to(committableSink(out))
  }
}
