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

package cloudflow.akkastream.util.javadsl

import scala.collection.JavaConverters._
import akka.NotUsed
import akka.japi.Pair
import akka.stream._
import akka.kafka.ConsumerMessage._
import cloudflow._
import cloudflow.akkastream._
import cloudflow.streamlets._

object Merger {
  def source[T](
      sources: java.util.List[cloudflow.akkastream.javadsl.SourceWithOffsetContext[T]]
  ): cloudflow.akkastream.javadsl.SourceWithOffsetContext[T] =
    cloudflow.akkastream.util.scaladsl.Merger.source(sources.asScala.map(_.asScala)).asJava

  def source[T](
      inlets: java.util.List[CodecInlet[T]],
      context: AkkaStreamletContext
  ): cloudflow.akkastream.javadsl.SourceWithOffsetContext[T] =
    cloudflow.akkastream.util.scaladsl.Merger.source(inlets.asScala)(context).asJava
}

/**
 * A `MergeLogic` merges two or more inlets into one outlet.
 * Elements from all inlets will be processed with at-least-once semantics. The elements will be processed
 * in semi-random order and with equal priority for all inlets.
 */
@deprecated("Use `Merger.source` instead.", "1.3.1")
final class MergeLogic[T](
    inletPorts: java.util.List[CodecInlet[T]],
    outlet: CodecOutlet[T],
    context: AkkaStreamletContext
) extends akkastream.util.scaladsl.MergeLogic(inletPorts.asScala.toIndexedSeq, outlet)(context)
