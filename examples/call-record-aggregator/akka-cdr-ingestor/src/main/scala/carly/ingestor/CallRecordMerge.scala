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

package carly.ingestor

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastream.util.scaladsl.Merger
import cloudflow.akkastream.util.scaladsl.Splitter

import carly.data._

class CallRecordMerge extends AkkaStreamlet {
  val in0 = AvroInlet[CallRecord]("in-0")
  val in1 = AvroInlet[CallRecord]("in-1")
  val in2 = AvroInlet[CallRecord]("in-2")

  val left  = AvroOutlet[InvalidRecord]("invalid", _.record)
  val right = AvroOutlet[CallRecord]("valid", _.user)

  private val oldDataWatermark = java.sql.Timestamp.valueOf("2010-01-01 00:00:00.000").getTime / 1000 //seconds

  final override val shape = StreamletShape.withInlets(in0, in1, in2).withOutlets(left, right)

  final override def createLogic = new RunnableGraphStreamletLogic() {
    def validationFlow =
      FlowWithCommittableContext[CallRecord]
        .map { record ⇒
          if (record.timestamp < oldDataWatermark) Left(InvalidRecord(record.toString, "Timestamp outside range!"))
          else Right(record)
        }

    def runnableGraph =
      Merger.source(in0, in1, in2).to(Splitter.sink(validationFlow, left, right))
  }
}
