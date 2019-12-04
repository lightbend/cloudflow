package model.tensorflow

import model.ModelDescriptorUtil.implicits._
import model.ModelBase
import modelserving.model.ModelDescriptor
import org.tensorflow.{ Graph, Session }

/**
 * Abstract class for any TensorFlow (optimized export) model processing. It has to be extended by the user
 * implement score method, based on his own model. Serializability here is required for Spark.
 */
abstract class TensorFlowModel[RECORD, MODEL_OUTPUT](descriptor: ModelDescriptor)(makeDefaultModelOutput: () ⇒ MODEL_OUTPUT)
  extends ModelBase[RECORD, MODEL_OUTPUT](descriptor)(makeDefaultModelOutput) with Serializable {

  assert(descriptor.modelBytes != None, s"Invalid descriptor ${descriptor.toString}")

  type Signatures = Map[String, Signature]

  // Model graph
  val graph = new Graph
  graph.importGraphDef(descriptor.modelBytes.get)
  // Create TensorFlow session
  val session = new Session(graph)

  override def cleanup(): Unit = {
    try {
      session.close
    } catch {
      case _: Throwable ⇒ // Swallow
    }
    try {
      graph.close
    } catch {
      case _: Throwable ⇒ // Swallow
    }
  }
}
