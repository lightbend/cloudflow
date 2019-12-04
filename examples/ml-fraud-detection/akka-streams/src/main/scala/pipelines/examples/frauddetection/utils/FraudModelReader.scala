package pipelines.examples.frauddetection.utils

import modelserving.model.{ ModelDescriptor, ModelType }
import pipelinesx.ingress.ByteArrayReader

/**
 * Provides an infinite stream of wine records, repeatedly reading them from
 * the specified resource.
 */
final case class FraudModelReader(resourceNames: Map[ModelType, Seq[String]]) {

  assert(resourceNames.size > 0)

  protected var currentModelType: ModelType = ModelType.TENSORFLOW
  protected var currentIndex = 0
  init(ModelType.TENSORFLOW)

  // ModelTypes defined in the Avro files: ["TENSORFLOW", "TENSORFLOWSAVED", "TENSORFLOWSERVING"]
  def next(): ModelDescriptor = currentModelType match {
    case ModelType.TENSORFLOW if finished(ModelType.TENSORFLOW, currentIndex) ⇒
      next()

    case ModelType.TENSORFLOW ⇒
      val resourceName = resourceNames(ModelType.TENSORFLOW)(currentIndex)
      val barray = readBytes(resourceName)
      currentIndex += 1
      new ModelDescriptor(
        modelType = ModelType.TENSORFLOW,
        modelName = s"Tensorflow Model - $resourceName",
        description = "generated from TensorFlow",
        modelBytes = Some(barray),
        modelSourceLocation = None)

    case ModelType.TENSORFLOWSERVING | ModelType.TENSORFLOWSAVED ⇒
      Console.err.println(
        s"BUG! currentModelType = $currentModelType should not be set! Using TENSORFLOW")
      init(ModelType.TENSORFLOW)
      next()
  }

  protected def readBytes(source: String): Array[Byte] =
    ByteArrayReader.fromClasspath(source) match {
      case Left(error) ⇒
        throw new IllegalArgumentException(error) // TODO: return Either from readBytes!
      case Right(array) ⇒ array
    }

  protected def finished(modelType: ModelType, currentIndex: Int): Boolean =
    resourceNames.get(modelType) match {
      case None                                      ⇒ true
      case Some(names) if currentIndex >= names.size ⇒ true
      case _                                         ⇒ false
    }

  protected def init(whichType: ModelType): Unit = {
    currentModelType = whichType
    currentIndex = 0
    if (finished(whichType, 0))
      println(s"WARNING: No resources specified for model type $whichType")
  }
}
