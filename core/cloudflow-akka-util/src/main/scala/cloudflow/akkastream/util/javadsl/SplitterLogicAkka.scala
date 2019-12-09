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
import cloudflow.akkastream.javadsl._
import cloudflow.akkastream.javadsl.util.{ Either ⇒ JEither }
import cloudflow.streamlets._

abstract class SplitterLogicAkka[I, L, R](
    in: CodecInlet[I],
    left: CodecOutlet[L],
    right: CodecOutlet[R],
    context: AkkaStreamletContext
) extends akkastream.util.scaladsl.SplitterLogicAkka(in, left, right)(context) {

  def createFlow(): FlowWithOffsetContext[I, JEither[L, R]]
  def flow: scaladsl.FlowWithOffsetContext[I, Either[L, R]] = {
    createFlow().map(jEither ⇒ if (jEither.isRight) Right(jEither.get()) else Left(jEither.getLeft())).asScala
  }
  final def createFlowWithOffsetContext() = FlowWithOffsetContext.create[I]()
}
