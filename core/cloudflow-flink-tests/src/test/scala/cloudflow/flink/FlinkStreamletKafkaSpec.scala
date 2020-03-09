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

import com.typesafe.config.ConfigFactory
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api._

import cloudflow.streamlets.{ StreamletDefinition, StreamletShape }
import cloudflow.streamlets.avro.AvroOutlet
import cloudflow.flink.avro._
import cloudflow.flink.testkit._
import org.scalatest._

/**
 * This test needs a local running Kafka to run. Will be useful to do some testing on an
 * actual running Kafka with streamlets.
 */
class FlinkStreamletKafkaSpec extends FlinkTestkit with WordSpecLike with Matchers with BeforeAndAfterAll {

  "FlinkIngress" ignore {
    "write streaming data from a source" in {
      @transient lazy val env = StreamExecutionEnvironment.getExecutionEnvironment
      configureCheckpoint(env)

      object FlinkIngress extends FlinkStreamlet {
        val out   = AvroOutlet[Data]("out", _.id.toString())
        val shape = StreamletShape(out)

        override def createLogic() = new FlinkStreamletLogic {
          override def buildExecutionGraph = {
            val data                  = (1 to 10).map(i ⇒ new Data(i, s"name$i"))
            val ins: DataStream[Data] = env.addSource(FlinkSource.CollectionSourceFunction(data))
            writeStream(out, ins)
          }
        }
      }

      val streamletDef = StreamletDefinition("appId", "FlinkIngress", "streamletClass", List(), List(), ConfigFactory.empty)
      val ctx          = new FlinkStreamletContextImpl(streamletDef, env, ConfigFactory.empty)
      FlinkIngress.setContext(ctx)
      FlinkIngress.run(ctx.config)
    }
  }

  private def configureCheckpoint(env: StreamExecutionEnvironment): Unit = {
    // start a checkpoint every 1000 ms
    env.enableCheckpointing(1000)
    // set mode to exactly-once (this is the default)
    env.getCheckpointConfig.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE)
    // make sure 500 ms of progress happen between checkpoints
    env.getCheckpointConfig.setMinPauseBetweenCheckpoints(500)
    // checkpoints have to complete within one minute, or are discarded
    env.getCheckpointConfig.setCheckpointTimeout(60000)
    // prevent the tasks from failing if an error happens in their checkpointing, the checkpoint will just be declined.
    env.getCheckpointConfig.setTolerableCheckpointFailureNumber(3)
    // allow only one checkpoint to be in progress at the same time
    env.getCheckpointConfig.setMaxConcurrentCheckpoints(1)
  }
}
