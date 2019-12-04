/*
 * Copyright (C) 2017-2019  Lightbend
 *
 * This file is part of the Lightbend model-serving-tutorial (https://github.com/lightbend/model-serving-tutorial)
 *
 * The model-serving-tutorial is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License Version 2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package model

import modelserving.model.{ ModelDescriptor, ModelType }

import scala.concurrent.duration._

/**
 * Model serving statistics definition.
 * TODO: Assumes that
 */
final case class ModelServingStats(
    modelType:   ModelType      = ModelType.UNKNOWN,
    modelName:   String         = "",
    description: String         = "",
    scoreCount:  Long           = 0,
    since:       FiniteDuration = System.currentTimeMillis().milliseconds,
    duration:    FiniteDuration = 0.milliseconds,
    min:         FiniteDuration = 1000000.milliseconds, // arbitrary, but reasonable
    max:         FiniteDuration = 0.milliseconds) {

  /**
   * Increment model serving statistics; invoked after scoring every record.
   * @param executionTime Long value for the milliseconds it took to score the record.
   */
  def incrementUsage(executionTime: FiniteDuration): ModelServingStats =
    this.copy(
      scoreCount = this.scoreCount + 1,
      duration = this.duration + executionTime,
      min = if (executionTime < this.min) executionTime else this.min,
      max = if (executionTime > this.min) executionTime else this.max)
}

object ModelServingStats {
  def apply(descriptor: ModelDescriptor): ModelServingStats =
    new ModelServingStats(
      modelType = descriptor.modelType,
      modelName = descriptor.modelName,
      description = descriptor.description)

  val unknown = new ModelServingStats()
}
