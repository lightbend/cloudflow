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

package modelserving

import java.io.File
import java.nio.file._

import scala.collection.JavaConverters._
import scala.collection.mutable.{ Map ⇒ MMap }
import scala.util.control.NonFatal
import scala.util.Try

import com.google.protobuf.Descriptors
import org.tensorflow.framework._
import org.tensorflow.{ Graph, SavedModelBundle, Session, Tensor }
import modelserving.wine.avro._

import TensorFlowModelBundle._

/**
 * Encapsulates TensorFlow scoring using a [[LoadedModel]] (from a SavedModelBundle).
 */
trait TensorFlowModel[Record, ServingResult] {
  def loadedModel: LoadedModel

  def emptyServingResult: ServingResult
  def toTensor(record: Record): Tensor[_]
  def invokeModel(record: Record): Either[String, ServingResult]

  val startTime = System.currentTimeMillis

  /**
   * Score a record with the model
   */
  def score(record: Record): (ServingResult, ModelResultMetadata) = {
    val start = System.currentTimeMillis()
    val (errors, modelOutput) = invokeModel(record) match {
      case Left(errors)  ⇒ (errors, emptyServingResult)
      case Right(output) ⇒ ("", output)
    }
    val duration       = (System.currentTimeMillis() - start)
    val resultMetadata = ModelResultMetadata(errors, loadedModel.modelName, startTime, duration)
    (modelOutput, resultMetadata)
  }

  def cleanup() = loadedModel.cleanup()
}

/**
 * Contains TensorFlow model resources loaded from a SavedModelBundle.
 */
final case class LoadedModel(
    modelName: String,
    graph: Graph,
    session: Session,
    signatures: Map[String, Signature]
) {

  /**
   * Cleans up used session and graph
   */
  def cleanup(): Unit =
    try {
      session.close
    } finally {
      graph.close
    }
}

/**
 * Loads a model from a TensorFlow SavedModelBundle
 * @param modelName the name of the model
 * @param savedModelBundlePath the path to the SavedModelBundle
 * @param createModel a function to create a specific model from a LoadedModel
 */
object TensorFlowModelBundle {
  def load[Record, ServingResult, Model](
      savedModelBundlePath: Path,
      modelName: String,
      createModel: LoadedModel ⇒ Model,
      tags: Path ⇒ Seq[String] = firstTag
  ): Try[Model] =
    Try {
      val bundle = SavedModelBundle.load(savedModelBundlePath.toAbsolutePath.toString, tags(savedModelBundlePath): _*)
      val graph  = bundle.graph
      // get metatagraph and signature
      val metaGraphDef = MetaGraphDef.parseFrom(bundle.metaGraphDef)
      val signatureMap = metaGraphDef.getSignatureDefMap.asScala
      //  parse signature, so that we can use definitions (if necessary) programmatically in score method
      // Create TensorFlow session
      val session = bundle.session
      createModel(LoadedModel(modelName, graph, session, parseSignatures(signatureMap)))
    }

  /**
   * Parse signatures
   *
   * @param signatures - signatures from metagraph
   * @returns map of names/signatures
   */
  private def parseSignatures(signatures: MMap[String, SignatureDef]): Map[String, Signature] =
    signatures
      .map(signature ⇒
        signature._1 -> Signature(parseInputOutput(signature._2.getInputsMap.asScala), parseInputOutput(signature._2.getOutputsMap.asScala))
      )
      .toMap

  /**
   * Gets all tags in the saved bundle and uses the first one.
   * @param directory - directory for saved model
   * @returns sequence of tags
   */
  private def firstTag(directory: Path): Seq[String] = {

    val directoryFile = directory.toFile
    val pbfiles =
      if (directoryFile.exists && directoryFile.isDirectory)
        directoryFile.listFiles.filter(_.isFile).filter(name ⇒ (name.getName.endsWith("pb") || name.getName.endsWith("pbtxt"))).toList
      else
        List[File]()
    if (pbfiles.length > 0) {
      val byteArray = Files.readAllBytes(pbfiles(0).toPath)
      SavedModel
        .parseFrom(byteArray)
        .getMetaGraphsList
        .asScala
        .flatMap(graph ⇒ graph.getMetaInfoDef.getTagsList.asByteStringList.asScala.map(_.toStringUtf8))
    } else {
      Seq.empty
    }
  }

  /**
   * Parse Input/Output
   *
   * @param inputOutputs - Input/Output definition from metagraph
   * @returns map of names/fields
   */
  private def parseInputOutput(inputOutputs: MMap[String, TensorInfo]): Map[String, Field] =
    inputOutputs.map {
      case (key, info) ⇒
        var name                                   = ""
        var dtype: Descriptors.EnumValueDescriptor = null
        var shape                                  = Seq.empty[Int]
        info.getAllFields.asScala.foreach { descriptor ⇒
          val fieldName = descriptor._1.getName
          if (fieldName.contains("shape")) {
            descriptor._2
              .asInstanceOf[TensorShapeProto]
              .getDimList
              .toArray
              .map(d ⇒ d.asInstanceOf[TensorShapeProto.Dim].getSize)
              .toSeq
              .foreach(v ⇒ shape = shape :+ v.toInt)
          }
          if (fieldName.contains("name")) {
            name = descriptor._2.toString.split(":")(0)
          }
          if (fieldName.contains("dtype")) {
            dtype = descriptor._2.asInstanceOf[Descriptors.EnumValueDescriptor]
          }
        }
        key -> Field(name, dtype, shape)
    }.toMap

  /** Definition of the field (input/output) */
  case class Field(name: String, `type`: Descriptors.EnumValueDescriptor, shape: Seq[Int])

  /** Definition of the signature */
  case class Signature(inputs: Map[String, Field], outputs: Map[String, Field])
}
