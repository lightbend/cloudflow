package modelserving.wine

import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.{ FlowWithOffsetContext, RunnableGraphStreamletLogic }
import cloudflow.streamlets.avro.{ AvroInlet, AvroOutlet }
import cloudflow.streamlets.{ ReadWriteMany, StreamletShape, StringConfigParameter, VolumeMount }

import modelserving.wine.avro._

/**
 * Scores WineRecords using a TensorFlow model that is read from a volume mount.
 * The model used can be changed by deploying or reconfiguring this streamlet using `kubectl cloudflow deploy`
 * or `kubectl cloudflow configure`.
 */
final class WineModelServer extends AkkaStreamlet {

  val in = AvroInlet[WineRecord]("wine-records")
  val out = AvroOutlet[WineResult]("wine-results", _.inputRecord.datatype)

  final override val shape = StreamletShape.withInlets(in).withOutlets(out)

  // the volume mount where all models are read from
  private val modelBundleMount = VolumeMount("models", "/models", ReadWriteMany)
  override def volumeMounts = Vector(modelBundleMount)

  // the relative directory under `/models` which contains a TensorFlow SavedModelBundle.
  val ModelName = StringConfigParameter(
    "model",
    "Provide the TensorFlow SavedModelBundle model directory-name under /models to load the model from"
  )

  override def configParameters = Vector(ModelName)

  override final def createLogic = new RunnableGraphStreamletLogic() {
    val modelName = streamletConfig.getString(ModelName.key)
    val savedModelBundlePath = getMountedPath(modelBundleMount).resolve(modelName)

    log.info(s"Loading model from $savedModelBundlePath.")

    val modelScoringFlow = WineModelBundle.load(savedModelBundlePath, modelName).fold(
      e ⇒ {
        log.error(s"Could not load model from $savedModelBundlePath.", e)
        FlowWithOffsetContext[WineRecord]
          .map(record ⇒ WineResult(record, WineModel.EmptyServingResult, ModelResultMetadata(s"Could not load model: ${e.getMessage}")))
      },
      model ⇒ {
        log.info(s"Loaded model from $savedModelBundlePath.")
        FlowWithOffsetContext[WineRecord]
          .map(record ⇒ model.scoreWine(record))
      }
    )

    def runnableGraph() = {
      sourceWithOffsetContext(in)
        .via(modelScoringFlow)
        .to(sinkWithOffsetContext(out))
    }
  }
}
