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

package cloudflow.akkastream

import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters._
import scala.concurrent.Future

import akka.stream._
import akka.kafka.ConsumerMessage.Committable
/**
 * Extends [[akka.stream.SinkRef]] with a `write` method that can be used to
 * write data directly to the implementation that `SinkRef.sink` writes to.
 * Using the `write` method can be more convenient, especially
 * when you want to write one value at a time and continue only once the write has completed.
 * The alternative would be to use:
 * {{{
 *  Source.single(value).runWith(sink)))
 * }}}
 * but in that case it is not known when the value has been written.
 */
trait WritableSinkRef[T] extends SinkRef[(T, Committable)] {
  /**
   * Writes the value to this SinkRef. The Future contains the written value
   * once the value has been written.
   */
  def write(value: T): Future[T]

  /**
   * Java API
   */
  def writeJava(value: T): CompletionStage[T] = write(value).toJava
}
