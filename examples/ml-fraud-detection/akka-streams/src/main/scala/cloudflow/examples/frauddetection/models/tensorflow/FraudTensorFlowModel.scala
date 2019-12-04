package cloudflow.examples.frauddetection.models.tensorflow

import cloudflow.examples.frauddetection.data.CustomerTransaction
import model.{ Model, ModelFactory }
import model.tensorflow.TensorFlowModel
import modelserving.model.ModelDescriptor
import org.tensorflow.Tensor

import scala.util.Random

/**
 * TensorFlow model implementation for wine data
 */
class FraudTensorFlowModel(descriptor: ModelDescriptor)
  extends TensorFlowModel[CustomerTransaction, Double](descriptor)(() ⇒ 0.0) {

  import FraudTensorFlowModel._

  /** Score a wine record with the model */
  override protected def invokeModel(record: CustomerTransaction): Either[String, Double] = {
    try {
      // Create modelInput tensor
      val modelInput = toTensor(record)
      // Serve model using TensorFlow APIs
      val result = session.runner.feed("dense_1_input", modelInput).fetch("dense_5/Sigmoid").run().get(0)
      // Get result shape
      val rshape = result.shape
      // Map output tensor to shape
      val rMatrix = Array.ofDim[Float](rshape(0).asInstanceOf[Int], rshape(1).asInstanceOf[Int])
      result.copyTo(rMatrix)
      // Get result
      Right(r(rMatrix(0).indices.maxBy(rMatrix(0)).toDouble))
    } catch {
      case t: Throwable ⇒ Left(t.getMessage)
    }
  }
}

object FraudTensorFlowModel {

  def toTensor(record: CustomerTransaction): Tensor[_] = {
    val data = Array(
      record.time,
      record.v1,
      record.v2,
      record.v3,
      record.v4,
      record.v5,
      record.v6,
      record.v7,
      record.v9,
      record.v10,
      record.v11,
      record.v12,
      record.v14,
      record.v16,
      record.v17,
      record.v18,
      record.v19,
      record.v21,
      record.amount)
    Tensor.create(Array(data))
  }

  def r(score: Double) = {
    if (Random.nextInt(100) > 93)
      Random.nextDouble()
    else
      score
  }
}

/** Factory for wine data TensorFlow model */
object FraudTensorFlowModelFactory extends ModelFactory[CustomerTransaction, Double] {

  def make(descriptor: ModelDescriptor): Either[String, Model[CustomerTransaction, Double]] =
    Right(new FraudTensorFlowModel(descriptor))
}
