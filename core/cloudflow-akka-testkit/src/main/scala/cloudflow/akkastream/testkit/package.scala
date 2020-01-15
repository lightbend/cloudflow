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

package testkit {
  import scala.concurrent.Future
  import akka.{ Done, NotUsed }
  import akka.stream.scaladsl._
  import cloudflow.streamlets.CodecOutlet
  import akka.kafka.ConsumerMessage._

  trait InletTap[T] {
    def portName: String

    // This is for internal usage so using a scaladsl Source and a Tuple is no problem
    private[testkit] def source: Source[(T, Committable), NotUsed]
  }

  trait OutletTap[T] {
    def outlet: CodecOutlet[T]
    def portName: String = outlet.name

    // This is for internal usage so using a scaladsl Source is no problem
    private[testkit] def sink: Sink[PartitionedValue[T], Future[Done]]

    private[testkit] def toPartitionedValue(element: T): PartitionedValue[T] =
      PartitionedValue(outlet.partitioner(element), element)
  }

  /**
   * A representation of a key-value pair that is not bound to the Scala or Java DSLs
   */
  case class PartitionedValue[T](key: String, value: T) {
    def getKey(): String = key
    def getValue(): T = value
  }

  trait ConfigParameterValue {
    def configParameterKey: String
    def value: String
  }
}
