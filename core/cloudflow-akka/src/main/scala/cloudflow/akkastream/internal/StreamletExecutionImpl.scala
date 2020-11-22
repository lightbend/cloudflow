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

package cloudflow.akkastream.internal

import akka.Done
import akka.annotation.InternalApi
import cloudflow.akkastream.AkkaStreamletContext
import cloudflow.streamlets.{ Dun, StreamletExecution }

import scala.concurrent.{ Future, Promise }
import scala.util.Try

@InternalApi
final class StreamletExecutionImpl(owner: AkkaStreamletContext) extends StreamletExecution {
  private val readyPromise                         = Promise[Dun]()
  private[akkastream] val completionPromise        = Promise[Dun]()
  private[akkastream] def signalReady()            = readyPromise.trySuccess(Dun)
  private[akkastream] def complete(res: Try[Done]) = completionPromise.tryComplete(res.map(_ => Dun))
  private[akkastream] def complete(): Future[Dun] = {
    completionPromise.trySuccess(Dun)
    completed
  }

  override val ready: Future[Dun]     = readyPromise.future
  override def stop(): Future[Dun]    = owner.stop()
  override val completed: Future[Dun] = completionPromise.future
}
