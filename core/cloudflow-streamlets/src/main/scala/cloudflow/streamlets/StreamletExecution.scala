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

import scala.concurrent.Future

/**
 * A handle to the running [[Streamlet]].
 */
trait StreamletExecution {

  /**
   * Completes when the streamlet is ready to start processing.
   */
  def ready: Future[Dun]

  /**
   * Stops the streamlet.
   */
  def stop(): Future[Dun]

  /**
   * Completes when the streamlet is completed.
   */
  def completed: Future[Dun]
}
