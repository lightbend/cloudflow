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

package cloudflow.spark

import scala.reflect.runtime.universe._
import org.apache.spark.sql.{ Dataset, Encoder, SparkSession }
import org.apache.spark.sql.streaming.{ OutputMode, StreamingQuery }
import cloudflow.streamlets.{ CodecInlet, CodecOutlet }
import cloudflow.streamlets._

abstract case class SparkStreamletContext(
    private[cloudflow] override val streamletDefinition: StreamletDefinition,
    session: SparkSession
) extends StreamletContext {

  /**
   * Returns the absolute path to a mounted shared storage that can be used to store reliable checkpoints.
   * Reliable checkpoints lets the Spark application persist its state across restarts and restart from
   * where it last stopped.
   * @param dirName: the specific folder name to use for this application. It must be distinct for each
   *                  query executed in the scope of this streamlet
   * @return the absolute path to a mounted shared storage
   */
  def checkpointDir(dirName: String): String

  /**
   * Stream from the underlying external storage and return a DataFrame
   *
   * @param inPort the inlet port to read from
   * @return the data read as `Dataset[In]`
   */
  def readStream[In](inPort: CodecInlet[In])(implicit encoder: Encoder[In], typeTag: TypeTag[In]): Dataset[In]

  /**
   * Start the execution of a StreamingQuery that writes the encodedStream to
   * an external storage using the designated portOut
   *
   * @param stream stream used to write the result of execution of the `StreamingQuery`
   * @param outPort the port used to write the result of execution of the `StreamingQuery`
   * @param outputMode the output mode used to write. Valid values Append, Update, Complete
   *
   * @return the `StreamingQuery` that starts executing
   */
  def writeStream[Out](stream: Dataset[Out], outPort: CodecOutlet[Out], outputMode: OutputMode)(implicit encoder: Encoder[Out],
                                                                                                typeTag: TypeTag[Out]): StreamingQuery

}
