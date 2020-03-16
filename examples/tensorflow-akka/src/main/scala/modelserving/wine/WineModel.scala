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

package modelserving.wine

import java.nio.file.Path

import scala.util.control.NonFatal

import org.tensorflow.Tensor

import modelserving._
import modelserving.wine.avro._

/**
 * Loads [[WineModel]]s from a TensorFlow SavedModelBundle.
 * @param savedModelBundlePath the path to the SavedModelBundle
 * @param modelName the name of the model that will be used in ModelResultMetadata.
 */
object WineModelBundle {
  def load(savedModelBundlePath: Path, modelName: String) = TensorFlowModelBundle.load(savedModelBundlePath, modelName, WineModel.apply)
}

object WineModel {
  val EmptyServingResult = WineServingResult(.0)
}

/**
 * Serves wine quality scores.
 */
final case class WineModel(val loadedModel: LoadedModel) extends TensorFlowModel[WineRecord, WineServingResult] {
  val session    = loadedModel.session
  val signatures = loadedModel.signatures

  def emptyServingResult = WineModel.EmptyServingResult

  /**
   * Scores WineRecords.
   */
  def scoreWine(record: WineRecord): WineResult = {
    val (servingResult, modelResultMetadata) = score(record)
    WineResult(record, servingResult, modelResultMetadata)
  }

  /**
   * invokes the TensorFlow bundled model for wine scoring.
   */
  def invokeModel(record: WineRecord): Either[String, WineServingResult] =
    try {
      // Create record tensor
      val modelInput = toTensor(record)

      // Serve model using TensorFlow APIs
      val signature = signatures.head._2
      val tinput    = signature.inputs.head._2
      val toutput   = signature.outputs.head._2
      val result    = session.runner.feed(tinput.name, modelInput).fetch(toutput.name).run().get(0)
      // process result
      val rshape  = result.shape
      val rMatrix = Array.ofDim[Float](rshape(0).asInstanceOf[Int], rshape(1).asInstanceOf[Int])
      result.copyTo(rMatrix)
      Right(WineServingResult(rMatrix(0).indices.maxBy(rMatrix(0)).toDouble))
    } catch {
      case NonFatal(e) ⇒ Left(e.getMessage)
    }

  /**
   * Converts incoming WineRecord to Tensor.
   */
  def toTensor(record: WineRecord): Tensor[_] = {
    val data = Array(
      record.fixed_acidity.toFloat,
      record.volatile_acidity.toFloat,
      record.citric_acid.toFloat,
      record.residual_sugar.toFloat,
      record.chlorides.toFloat,
      record.free_sulfur_dioxide.toFloat,
      record.total_sulfur_dioxide.toFloat,
      record.density.toFloat,
      record.pH.toFloat,
      record.sulphates.toFloat,
      record.alcohol.toFloat
    )
    Tensor.create(Array(data))
  }
}
