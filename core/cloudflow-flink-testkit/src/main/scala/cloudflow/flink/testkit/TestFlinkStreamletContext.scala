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

package cloudflow.flink
package testkit

import org.apache.flink.streaming.api.scala._
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.streaming.api.datastream.DataStreamSink

import com.typesafe.config._
import cloudflow.streamlets._

/**
 * An implementation of `FlinkStreamletContext` for unit testing.
 */
@deprecated("Use contrib-sbt-flink library instead, see https://github.com/lightbend/cloudflow-contrib", "2.2.0")
class TestFlinkStreamletContext(
    override val streamletRef: String,
    env: StreamExecutionEnvironment,
    inletTaps: Seq[FlinkInletTap[_]],
    outletTaps: Seq[FlinkOutletTap[_]],
    override val config: Config = ConfigFactory.empty)
    extends FlinkStreamletContext(
      StreamletDefinition("appId", "appVersion", streamletRef, "streamletClass", List(), List(), config),
      env) {

  TestFlinkStreamletContext.result.clear()

  /**
   * Returns a `DataStream[In]` from the `inlet` to be added as the data source
   * of the computation graph
   */
  override def readStream[In: TypeInformation](inlet: CodecInlet[In]): DataStream[In] =
    inletTaps
      .find(_.portName == inlet.name)
      .map(_.inStream.asInstanceOf[DataStream[In]])
      .getOrElse(
        throw TestContextException(inlet.name, s"Bad test context, could not find source for inlet ${inlet.name}"))

  /**
   * Adds a sink to the `stream`. In the current implementation the sink just adds
   * the data to a concurrent collection for testing
   */
  override def writeStream[Out: TypeInformation](
      outlet: CodecOutlet[Out],
      stream: DataStream[Out]): DataStreamSink[Out] =
    outletTaps
      .find(_.portName == outlet.name)
      .map { _ =>
        stream.addSink(new SinkFunction[Out]() {
          override def invoke(out: Out) =
            TestFlinkStreamletContext.result.add(out.toString())
        })
      }
      .getOrElse(throw TestContextException(
        outlet.name,
        s"Bad test context, could not find destination for outlet ${outlet.name}"))
}

@deprecated("Use contrib-sbt-flink library instead, see https://github.com/lightbend/cloudflow-contrib", "2.2.0")
object TestFlinkStreamletContext {
  val result = new java.util.concurrent.ConcurrentLinkedQueue[String]()
}

@deprecated("Use contrib-sbt-flink library instead, see https://github.com/lightbend/cloudflow-contrib", "2.2.0")
case class TestContextException(portName: String, msg: String) extends RuntimeException(msg)
