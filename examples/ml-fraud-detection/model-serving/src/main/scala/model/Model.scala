package model

import modelserving.model.{ ModelDescriptor, ModelResultMetadata }

import scala.concurrent.duration._

/**
 * Abstraction for a machine learning model in memory.
 * `INRECORD` is the type of the records sent to the model for scoring.
 * `MODEL_OUTPUT` is the type of results the actual model implementation returns,
 * e.g., a "score".
 */
trait Model[INRECORD, MODEL_OUTPUT] {

  val descriptor: ModelDescriptor

  /** Score a record with the model and update the running statistics. */
  def score(record: INRECORD, stats: ModelServingStats): Model.ModelReturn[MODEL_OUTPUT]

  /** Hook for cleaning up resources */
  def cleanup(): Unit = {}
}

object Model {
  final case class ModelReturn[MODEL_OUTPUT](
      modelOutput:         MODEL_OUTPUT,
      modelResultMetadata: ModelResultMetadata,
      modelServingStats:   ModelServingStats)
}

/**
 * Generic definition of an operational machine learning model in memory, which
 * was constructed using the [[ModelDescriptor]] field.
 * @param descriptor the [[ModelDescriptor]] used to construct this instance.
 */
abstract class ModelBase[INRECORD, MODEL_OUTPUT](
    val descriptor: ModelDescriptor)(makeDefaultModelOutput: () ⇒ MODEL_OUTPUT)
  extends Model[INRECORD, MODEL_OUTPUT] {

  /**
   * Do implementation-specific scoring. On failure, an error string is returned in
   * the left. This will mean that the `MODEL_OUTPUT` will be initialized downstream
   * with default values and won't be meaningful!
   * @param the input record to score.
   * @return either a Left with any errors as a string or a Right with the output of invoking the model.
   */
  protected def invokeModel(input: INRECORD): Either[String, MODEL_OUTPUT]

  /**
   * Score a record with the model
   * @return the [[Model.ModelReturn]], including the result, scoring metadata (possibly including an error string), and some scoring metadata.
   */
  def score(record: INRECORD, stats: ModelServingStats): Model.ModelReturn[MODEL_OUTPUT] = {
    val start = System.currentTimeMillis()
    val (errors, modelOutput) = invokeModel(record) match {
      case Left(errors)  ⇒ (errors, makeDefaultModelOutput())
      case Right(output) ⇒ ("", output)
    }
    val duration = (System.currentTimeMillis() - start).milliseconds
    val resultMetadata = ModelResultMetadata(
      modelName = descriptor.modelName,
      modelType = descriptor.modelType.toString,
      startTime = start.milliseconds.length,
      duration = duration.length,
      errors = errors)
    Model.ModelReturn(modelOutput, resultMetadata, stats.incrementUsage(duration))
  }
}
