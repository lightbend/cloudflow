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

import scala.collection.immutable
import scala.jdk.CollectionConverters._

/**
 * Data class to support sending to multiple outlets from a single originating message.
 */
final class MultiData2[O1, O2] private (val data1: immutable.Seq[O1], val data2: immutable.Seq[O2]) {

  def withData1[O](data: immutable.Seq[O]) = new MultiData2[O, O2](data, data2)
  def withData2[O](data: immutable.Seq[O]) = new MultiData2[O1, O](data1, data)

  def getData1(): java.util.Collection[O1] = data1.asJavaCollection
  def getData2(): java.util.Collection[O2] = data2.asJavaCollection

  override def toString: String =
    "MultiData2(" +
        s"data1=${data1.mkString(",")}, " +
        s"data2=${data2.mkString(",")}" +
        ")"
}

object MultiData2 {

  def apply[O1, O2](data1: immutable.Seq[O1], data2: immutable.Seq[O2]) = new MultiData2(data1, data2)
  def createData1[O1, O2](data: immutable.Seq[O1]): MultiData2[O1, O2]  = new MultiData2(data, immutable.Seq.empty)
  def createData2[O1, O2](data: immutable.Seq[O2]): MultiData2[O1, O2]  = new MultiData2(immutable.Seq.empty, data)

  def fromEither[O1, O2](data: Either[O1, O2]): MultiData2[O1, O2] =
    data match {
      case Left(l)  => MultiData2.createData1(immutable.Seq(l))
      case Right(r) => MultiData2.createData2(immutable.Seq(r))
    }

  def create[O1, O2](data1: java.util.Collection[O1], data2: java.util.Collection[O2]) = new MultiData2(asScala(data1), asScala(data2))
  def createData1[O](data: java.util.Collection[O]): MultiData2[O, Object]             = new MultiData2(asScala(data), immutable.Seq.empty)
  def createData2[O](data: java.util.Collection[O]): MultiData2[Object, O]             = new MultiData2(immutable.Seq.empty, asScala(data))

  private def asScala[O](data: java.util.Collection[O]): immutable.Seq[O] = data.asScala.toIndexedSeq
}
