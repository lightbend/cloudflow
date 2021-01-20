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

package swissknife.akka

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastream.util.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import swissknife.data.Data


class AkkaTransformation extends AkkaStreamlet {
  val in    = AvroInlet[Data]("in")
  val out   = AvroOutlet[Data]("out").withPartitioner(RoundRobinPartitioner)
  val shape = StreamletShape(in).withOutlets(out)

  val configurableMessage = StringConfigParameter("configurable-message", "Configurable message.", Some("akka-original"))

  override def configParameters = Vector(configurableMessage)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val msg = configurableMessage.value
    def runnableGraph = sourceWithCommittableContext(in).via(flow).to(committableSink(out))
    def flow =
      FlowWithCommittableContext[Data]
        .map { data ⇒
          data.copy(src = data.src + "-akka", payload = msg)
        }
  }
}
