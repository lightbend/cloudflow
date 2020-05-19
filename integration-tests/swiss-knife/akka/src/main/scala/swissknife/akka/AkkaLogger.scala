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

package swissknife.akka

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import swissknife.data.Data

class AkkaLogger extends AkkaStreamlet {
  val inlet = AvroInlet[Data]("in")
  val shape = StreamletShape.withInlets(inlet)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val flow = FlowWithCommittableContext[Data]
      .map { data ⇒
        system.log.info(s"ts:${data.timestamp}, from:${data.src}, count: ${data.count}")
        data
      }

    def runnableGraph =
      sourceWithCommittableContext(inlet).via(flow).to(committableSink)
  }
}
