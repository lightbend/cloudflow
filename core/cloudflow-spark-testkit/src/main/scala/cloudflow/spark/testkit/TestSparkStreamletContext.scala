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
package testkit

import java.nio.file.attribute.FileAttribute

import com.typesafe.config._

import scala.reflect.runtime.universe._
import org.apache.spark.sql.{Dataset, Encoder, SparkSession}
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.streaming.{OutputMode, StreamingQuery, Trigger}
import cloudflow.streamlets._

/**
 * An implementation of `SparkCtx` for unit testing.
 *
 * `readStream` reads from a streaming data source (a `csv` in this case) and prepares
 *              a `Dataset[In]`
 *
 * `writeStream` returns a `StreamingQuery` that pushes the input `Dataset[Out]` to
 *              a `MemorySink`.
 */
private[testkit] class TestSparkStreamletContext(override val streamletRef: String,
                                                 session: SparkSession,
                                                 inletTaps: Seq[SparkInletTap[_]],
                                                 outletTaps: Seq[SparkOutletTap[_]],
                                                 override val config: Config = ConfigFactory.empty)
    extends SparkStreamletContext(StreamletDefinition("appId", "appVersion", streamletRef, "streamletClass", List(), List(), config),
                                  session) {

  override def readStream[In](inPort: CodecInlet[In])(implicit encoder: Encoder[In], typeTag: TypeTag[In]): Dataset[In] =
    inletTaps
      .find(_.portName == inPort.name)
      .map(_.instream.asInstanceOf[MemoryStream[In]].toDF.as[In])
      .getOrElse(throw TestContextException(inPort.name, s"Bad test context, could not find source for inlet ${inPort.name}"))

  override def writeStream[Out](stream: Dataset[Out],
                                outPort: CodecOutlet[Out],
                                outputMode: OutputMode)(implicit encoder: Encoder[Out], typeTag: TypeTag[Out]): StreamingQuery =
    outletTaps
      .find(_.portName == outPort.name)
      .map { outletTap ⇒
        stream.writeStream
          .outputMode(outputMode)
          .format("memory")
          .trigger(Trigger.Once)
          .queryName(outletTap.queryName)
          .start()
      }
      .getOrElse(throw TestContextException(outPort.name, s"Bad test context, could not find destination for outlet ${outPort.name}"))

  override def checkpointDir(dirName: String): String = {
    val fileAttibutes: Array[FileAttribute[_]] = Array()
    val tmpDir                                 = java.nio.file.Files.createTempDirectory("spark-test", fileAttibutes: _*)
    tmpDir.toFile.getAbsolutePath
  }
}

case class TestContextException(portName: String, msg: String) extends RuntimeException(msg)
