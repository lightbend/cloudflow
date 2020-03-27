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

package sensordata

import java.nio.file._

import akka.NotUsed
import akka.stream.IOResult
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl._
import akka.util.ByteString
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import spray.json.JsonParser

import scala.concurrent.Future
import scala.concurrent.duration._

class SensorDataFileIngress extends AkkaStreamlet {

  import SensorDataJsonSupport._

  val out   = AvroOutlet[SensorData]("out").withPartitioner(RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)

  //tag::volume-mount1[]
  private val sourceData    = VolumeMount("source-data-mount", "/mnt/data", ReadWriteMany)
  override def volumeMounts = Vector(sourceData)
  //end::volume-mount1[]

  // Streamlet processing steps
  // 1. Every X seconds
  // 2. Enumerate all files in the mounted path
  // 3. Read each file *)
  // 4. Deserialize file content to a SensorData value *)

  // *) Note that reading and deserializing the file content is done in separate steps for readability only, in production they should be merged into one step for performance reasons.

  override def createLogic = new RunnableGraphStreamletLogic() {
    //tag::volume-mount2[]
    val listFiles: NotUsed ⇒ Source[Path, NotUsed] = { _ ⇒
      Directory.ls(getMountedPath(sourceData))
    }
    //end::volume-mount2[]
    val readFile: Path ⇒ Source[ByteString, Future[IOResult]] = { path: Path ⇒
      FileIO.fromPath(path).via(JsonFraming.objectScanner(Int.MaxValue))
    }
    val parseFile: ByteString ⇒ SensorData = { jsonByteString ⇒
      JsonParser(jsonByteString.utf8String).convertTo[SensorData]
    }

    val emitFromFilesContinuously = Source
      .tick(1.second, 5.second, NotUsed)
      .flatMapConcat(listFiles)
      .flatMapConcat(readFile)
      .map(parseFile)
    def runnableGraph = emitFromFilesContinuously.to(plainSink(out))
  }

  // example of what not to do
  def doNot(): Unit = {
    //tag::volume-mount-bad[]
    val files = Directory.ls(FileSystems.getDefault().getPath("/mnt/data"))
    //end::volume-mount-bad[]
    files.toString
  }
}
