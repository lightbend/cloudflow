/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

import cloudflow._
import cloudflow.akkastream._
import cloudflow.streamlets._
import scala.collection.JavaConverters._

/**
 * A `MergeLogic` merges two or more inlets into one outlet.
 * Elements from all inlets will be processed with at-least-once semantics. The elements will be processed
 * in semi-random order and with equal priority for all inlets.
 */
final class MergeLogicAkka[T](
    inletPorts: java.util.List[CodecInlet[T]],
    outlet: CodecOutlet[T],
    context: AkkaStreamletContext
) extends akkastream.util.scaladsl.MergeLogicAkka(inletPorts.asScala.toIndexedSeq, outlet)(context)
