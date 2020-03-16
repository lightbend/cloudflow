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

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.datastream.DataStreamSink
import org.apache.flink.streaming.api.scala._

import cloudflow.streamlets._

/**
 * Runtime context for [[FlinkStreamlet]]s
 */
abstract case class FlinkStreamletContext(
    private[cloudflow] override val streamletDefinition: StreamletDefinition,
    @transient env: StreamExecutionEnvironment
) extends StreamletContext {

  /**
   * Read from the underlying external storage through the inlet `inPort` and return a DataStream
   *
   * @param inlet the inlet port to read from
   * @return the data read as `DataStream[In]`
   */
  def readStream[In: TypeInformation](inlet: CodecInlet[In]): DataStream[In]

  /**
   * Write to the external storage using the outlet `outPort` from the stream `stream`
   * and return the same stream
   *
   * @param outlet the outlet used to write the result of execution
   * @param stream stream used to write the result of execution
   *
   * @return the `DataStream` used to write to sink
   */
  def writeStream[Out: TypeInformation](outlet: CodecOutlet[Out], stream: DataStream[Out]): DataStreamSink[Out]
}
