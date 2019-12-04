package cloudflow.examples.frauddetection.models.tensorflow

import cloudflow.examples.frauddetection.data.CustomerTransaction
import model.{ Model, ModelFactory }
import model.tensorflow.TensorFlowBundleModel
import modelserving.model.ModelDescriptor

/**
 * Implementation of TensorFlow bundled model for Wine.
 */
class FraudTensorFlowBundledModel(descriptor: ModelDescriptor)
  extends TensorFlowBundleModel[CustomerTransaction, Double](descriptor)(() ⇒ 0.0) {

  override protected def invokeModel(record: CustomerTransaction): Either[String, Double] = {
    try {
      // Create record tensor
      val modelInput = FraudTensorFlowModel.toTensor(record)
      // Serve model using TensorFlow APIs
      val signature = signatures.head._2
      val tinput = signature.inputs.head._2
      val toutput = signature.outputs.head._2
      val result = session.runner.feed(tinput.name, modelInput).fetch(toutput.name).run().get(0)
      // process result
      val rshape = result.shape
      val rMatrix = Array.ofDim[Float](rshape(0).asInstanceOf[Int], rshape(1).asInstanceOf[Int])
      result.copyTo(rMatrix)
      Right(rMatrix(0).indices.maxBy(rMatrix(0)).toDouble)
    } catch {
      case t: Throwable ⇒ Left(t.getMessage)
    }
  }
}

/**
 * Implementation of TensorFlow bundled model factory.
 */
object FraudTensorFlowBundledModelFactory extends ModelFactory[CustomerTransaction, Double] {

  def make(descriptor: ModelDescriptor): Either[String, Model[CustomerTransaction, Double]] =
    Right(new FraudTensorFlowBundledModel(descriptor))
}

