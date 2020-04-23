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

import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.{ FlowWithCommittableContext, RunnableGraphStreamletLogic }
import cloudflow.streamlets.avro.{ AvroInlet, AvroOutlet }
import cloudflow.streamlets.{ ReadWriteMany, StreamletShape, StringConfigParameter, VolumeMount }

import modelserving.wine.avro._

/**
 * Scores WineRecords using a TensorFlow model that is read from a volume mount.
 * The model used can be changed by deploying or reconfiguring this streamlet using `kubectl cloudflow deploy`
 * or `kubectl cloudflow configure`.
 */
final class WineModelServer extends AkkaStreamlet {

  val in  = AvroInlet[WineRecord]("wine-records")
  val out = AvroOutlet[WineResult]("wine-results", _.inputRecord.datatype)

  final override val shape = StreamletShape.withInlets(in).withOutlets(out)

  // the volume mount where all models are read from
  private val modelBundleMount = VolumeMount("models", "/models", ReadWriteMany)
  override def volumeMounts    = Vector(modelBundleMount)

  // the relative directory under `/models` which contains a TensorFlow SavedModelBundle.
  val ModelName = StringConfigParameter(
    "model",
    "Provide the TensorFlow SavedModelBundle model directory-name under /models to load the model from"
  )

  override def configParameters = Vector(ModelName)

  override final def createLogic = new RunnableGraphStreamletLogic() {
    val modelName            = streamletConfig.getString(ModelName.key)
    val savedModelBundlePath = getMountedPath(modelBundleMount).resolve(modelName)

    log.info(s"Loading model from $savedModelBundlePath.")

    val modelScoringFlow = WineModelBundle
      .load(savedModelBundlePath, modelName)
      .fold(
        e ⇒ {
          log.error(s"Could not load model from $savedModelBundlePath.", e)
          FlowWithCommittableContext[WineRecord]
            .map(record ⇒ WineResult(record, WineModel.EmptyServingResult, ModelResultMetadata(s"Could not load model: ${e.getMessage}")))
        },
        model ⇒ {
          log.info(s"Loaded model from $savedModelBundlePath.")
          FlowWithCommittableContext[WineRecord]
            .map(record ⇒ model.scoreWine(record))
        }
      )

    def runnableGraph() =
      sourceWithCommittableContext(in)
        .via(modelScoringFlow)
        .to(committableSink(out))
  }
}
