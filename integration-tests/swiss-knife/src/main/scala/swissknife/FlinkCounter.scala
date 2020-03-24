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
package swissknife

import cloudflow.flink.FlinkStreamlet
import org.apache.flink.streaming.api.scala._
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import cloudflow.flink._

class FlinkCounter extends FlinkStreamlet {

  @transient val in  = AvroInlet[Data]("in")
  @transient val out = AvroOutlet[Data]("out", _.src)

  @transient val shape = StreamletShape.withInlets(in).withOutlets(out)

  override def createLogic() = new FlinkStreamletLogic {
    override def buildExecutionGraph = {
      val stream: DataStream[Data] =
        readStream(in)
          .map { data ⇒
            data.copy(src = data.src + "-flink")
          }
      writeStream(out, stream)
    }
  }

}
