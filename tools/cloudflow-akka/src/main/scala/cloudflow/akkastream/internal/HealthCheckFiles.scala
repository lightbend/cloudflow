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

package cloudflow.akkastream.internal

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }

object HealthCheckFiles {

  def createReady(streamletRef: String): Unit = createTempFile(readyFilename(streamletRef), streamletRef)
  def deleteReady(streamletRef: String): Unit = Files.deleteIfExists(Paths.get(readyFilename(streamletRef)))

  def createAlive(streamletRef: String): Unit = createTempFile(aliveFilename(streamletRef), streamletRef)
  def deleteAlive(streamletRef: String): Unit = Files.deleteIfExists(Paths.get(aliveFilename(streamletRef)))

  private def readyFilename(streamletRef: String) =
    s"${streamletRef}-ready.txt"

  private def aliveFilename(streamletRef: String) =
    s"${streamletRef}-live.txt"

  private def createTempFile(relativePath: String, streamletRef: String): Unit = {
    val tempDir = System.getProperty("java.io.tmpdir")
    val path = java.nio.file.Paths.get(tempDir, relativePath)

    Files.write(path, s"an akka streamlet $streamletRef".getBytes(StandardCharsets.UTF_8))
    path.toFile.deleteOnExit()
  }

}
