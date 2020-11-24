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

package cloudflow.streamlets

import org.slf4j.LoggerFactory

// This data converter is used for both Akka Streams and Flink
// Spark does not support optional, so we need a separate implementation for Spark

trait InletDataConverter[T] {
  def convertData(inlet: CodecInlet[T], data: Array[Byte]): Option[T]
}

case class DefaultInletDataConverter[T]() extends InletDataConverter[T] {

  val logger = LoggerFactory.getLogger(this.getClass)

  override def convertData(inlet: CodecInlet[T], data: Array[Byte]): Option[T] =
    try {
      Some(inlet.codec.decode(data))
    } catch {
      case t: Throwable =>
        logger.error(s"Input data $data can not be transformed and will be skipped")
        logger.error(s"Data transformation error ${t.getMessage}")
        None
    }
}
