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

package cloudflow.flink

import org.apache.flink.streaming.api.scala._
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import cloudflow.flink.avro._

object FlinkProcessor extends FlinkStreamlet {

  // Step 1: Define inlets and outlets. Note for the outlet you need to specify
  //         the partitioner function explicitly
  val in = AvroInlet[Data]("in")
  val out = AvroOutlet[Simple]("out", _.name)

  // Step 2: Define the shape of the streamlet. In this example the streamlet
  //         has 1 inlet and 1 outlet
  val shape = StreamletShape(in, out)

  // Step 3: Provide custom implementation of `FlinkStreamletLogic` that defines
  //         the behavior of the streamlet
  override def createLogic() = new FlinkStreamletLogic {
    override def buildExecutionGraph = {
      val ins: DataStream[Data] = readStream(in)
      val simples: DataStream[Simple] = ins.map(r ⇒ new Simple(r.name))
      writeStream(out, simples)
    }
  }
}
