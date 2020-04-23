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

import akka.NotUsed
import akka.stream.scaladsl._
import akka.kafka.ConsumerMessage.{ Committable, CommittableOffset }

package object scaladsl {

  type FlowWithCommittableContext[-In, +Out] = FlowWithContext[In, Committable, Out, Committable, NotUsed]

  type SourceWithCommittableContext[+T] = SourceWithContext[T, Committable, _]

  object FlowWithCommittableContext {

    /**
     * Creates a [[akka.stream.scaladsl.FlowWithContext FlowWithContext]] that makes it possible for Cloudflow to commit reads when
     * `StreamletLogic.atLeastOnceSource` and `StreamletLogic.atLeastOnceSink` is used.
     */
    def apply[In](): FlowWithCommittableContext[In, In] = FlowWithContext[In, Committable]
  }

  /** Deprecated API **/

  @deprecated("Use `FlowWithCommittableContext` instead.", "1.3.1")
  type FlowWithOffsetContext[-In, +Out] = FlowWithContext[In, CommittableOffset, Out, CommittableOffset, NotUsed]

  @deprecated("Use `SourceWithCommittableContext` instead.", "1.3.4")
  type SourceWithOffsetContext[+T] = SourceWithContext[T, CommittableOffset, _]

  @deprecated("Use `FlowWithCommittableContext` instead.", "1.3.1")
  object FlowWithOffsetContext {

    /**
     * Creates a [[akka.stream.scaladsl.FlowWithContext FlowWithContext]] that makes it possible for cloudflow to commit reads when
     * `StreamletLogic.atLeastOnceSource` and `StreamletLogic.atLeastOnceSink` is used.
     */
    @deprecated("Use `FlowWithCommittableContext` instead.", "1.3.1")
    def apply[In]() = FlowWithContext[In, CommittableOffset]
  }

}
