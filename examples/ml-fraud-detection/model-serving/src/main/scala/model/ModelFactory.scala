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

import model.ModelDescriptorUtil.implicits._
import modelserving.model.{ ModelDescriptor, ModelType }
import cloudflowx.logging.LoggingUtil

/**
 * Generic definition of a model factory
 */
trait ModelFactory[INRECORD, OUTRECORD] {

  /**
   * Define this method for concrete subclasses.
   * It's protected; end users call "create", while implementers define "make".
   * @param descriptor used to construct the model.
   */
  protected def make(descriptor: ModelDescriptor): Either[String, Model[INRECORD, OUTRECORD]]

  def create(descriptor: ModelDescriptor): Either[String, Model[INRECORD, OUTRECORD]] =
    try {
      make(descriptor)
    } catch {
      case scala.util.control.NonFatal(th) ⇒
        val thMsg = LoggingUtil.throwableToStrings(th).mkString("\n")
        Left(s"Model factory failed to create a model from descriptor ${descriptor.toRichString}. $thMsg")
    }
}

/**
 * A model factory that encapsulates several factories, which are selected by the model type
 * when `create` is called. If an appropriate model type is not found, then an error is
 * returned through a `Left[String]`.
 * @param modelFactories a map where each [[ModelFactory]] is associated with the [[ModelType]] it constructs.
 */
final case class MultiModelFactory[INRECORD, OUTRECORD](
    modelFactories: Map[ModelType, ModelFactory[INRECORD, OUTRECORD]])
  extends ModelFactory[INRECORD, OUTRECORD] {
  /**
   * Determine which model factory to use from the descriptor and create the model with it.
   *
   * Define this method for concrete subclasses.
   * It's protected; end users call "create", while implementers define "make".
   *
   * @param descriptor used to construct the model.
   */
  override protected def make(descriptor: ModelDescriptor): Either[String, Model[INRECORD, OUTRECORD]] = try {
    modelFactories.get(descriptor.modelType) match {
      case Some(factory) ⇒ factory.create(descriptor)
      case None ⇒
        Left(s"$prefix: No factory found for model type ${descriptor.modelType}. ${descStr(descriptor)}")

    }
  } catch {
    case scala.util.control.NonFatal(th) ⇒
      val thStr = LoggingUtil.throwableToString(th)
      Left(s"$prefix: ${descStr(descriptor)}. Failed with exception $thStr")
  }

  private val prefix = "MultiModelFactory.make(descriptor)"
  private def descStr(descriptor: ModelDescriptor) = s"descriptor = ${descriptor.toRichString}"
}

