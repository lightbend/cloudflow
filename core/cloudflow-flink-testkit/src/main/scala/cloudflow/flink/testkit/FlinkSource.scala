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

package cloudflow.flink
package testkit

import org.apache.flink.streaming.api.functions.source.SourceFunction
import org.apache.flink.streaming.api.functions.source.SourceFunction.SourceContext
import scala.collection.JavaConverters._

object FlinkSource {
  case class CollectionSourceFunction[T](data: Seq[T]) extends SourceFunction[T] {
    def cancel(): Unit = {}
    def run(ctx: SourceContext[T]): Unit = {
      data.foreach(d ⇒ ctx.collect(d))
    }
  }

  /**
   * Java API
   */
  def collectionSourceFunction[T](data: java.util.List[T]) =
    CollectionSourceFunction(data.asScala.toSeq)
}
