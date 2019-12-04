package model.actor

import akka.Done
import akka.actor.{ Actor, Props }
import akka.event.Logging
import model._
import ModelDescriptorUtil.implicits._
import modelserving.model.{ ModelDescriptor, ModelResultMetadata }
import org.apache.avro.specific.SpecificRecordBase

import scala.concurrent.duration._

/**
 * Actor that handles messages to update a model and to score records using the current model.
 * @param label used for identifying the app, e.g., as part of a file name for persistence of the current model.
 * @param modelFactory is used to create new models on demand, based on input `ModelDescriptor` instances.
 * @param makeDefaultModelOutput produces default "output" for a model.
 * @param defaultModelDescriptor is used as the default model when the ModelServingActor is started
 */
class ModelServingActor[RECORD, MODEL_OUTPUT](
    label:                  String,
    modelFactory:           ModelFactory[RECORD, MODEL_OUTPUT],
    makeDefaultModelOutput: () ⇒ MODEL_OUTPUT,
    defaultModelDescriptor: ModelDescriptor) extends Actor {

  val log = Logging(context.system, this)
  log.info(s"Creating ModelServingActor for $label")

  protected var currentModel: Option[Model[RECORD, MODEL_OUTPUT]] = None
  protected var currentStats: ModelServingStats = ModelServingStats.unknown

  override def preStart {
    if (defaultModelDescriptor != null)
      UpdateMLModel(defaultModelDescriptor)
  }

  override def receive: PartialFunction[Any, Unit] = {
    case descriptor: ModelDescriptor ⇒
      UpdateMLModel(descriptor)
      sender() ! Done

    // The typing in the the next two lines is a hack. If we have `case r: RECORD`,
    // the compiler complains that it can't check the type of RECORD (it could be
    // a Seq[_] for all it knows, and hence eliminated by erasure), but
    // SpecificRecordBase is a concrete type.
    case recordBase: SpecificRecordBase ⇒
      val record = recordBase.asInstanceOf[RECORD]
      val mr: Model.ModelReturn[MODEL_OUTPUT] = currentModel match {
        case Some(model) ⇒ model.score(record, currentStats)
        case None ⇒
          log.debug("No model is currently available for scoring")
          Model.ModelReturn[MODEL_OUTPUT](
            makeDefaultModelOutput(),
            new ModelResultMetadata(),
            currentStats.incrementUsage(0.milliseconds))
      }
      currentStats = mr.modelServingStats
      if (currentStats.scoreCount % 100 == 0) {
        log.debug(s"Current statistics: $currentStats")
      }
      sender() ! mr

    case _: GetState ⇒
      sender() ! currentStats

    case unknown ⇒
      log.error(s"ModelServingActor: Unknown actor message received: $unknown")
  }

  def UpdateMLModel(descriptor: ModelDescriptor): Unit = {
    log.info(s"Received new model from descriptor: ${descriptor.toRichString}...")

    modelFactory.create(descriptor) match {
      case Right(newModel) ⇒
        // Log old model stats and clean up, if necessary:
        log.info(s"  Previous model statistics: $currentStats")
        currentModel.map(_.cleanup())
        // Update current model and reset state
        currentModel = Some(newModel)
        currentStats = ModelServingStats(newModel.descriptor)
      case Left(error) ⇒
        log.error(s"  Failed to instantiate the model: $error")
    }
  }
}

object ModelServingActor {

  def props[RECORD, MODEL_OUTPUT](
      label:                  String,
      modelFactory:           ModelFactory[RECORD, MODEL_OUTPUT],
      makeDefaultModelOutput: () ⇒ MODEL_OUTPUT): Props =
    Props(new ModelServingActor[RECORD, MODEL_OUTPUT](label, modelFactory, makeDefaultModelOutput, null))

  def props[RECORD, MODEL_OUTPUT](
      label:                  String,
      modelFactory:           ModelFactory[RECORD, MODEL_OUTPUT],
      makeDefaultModelOutput: () ⇒ MODEL_OUTPUT,
      defaultModelDescriptor: ModelDescriptor): Props =
    Props(new ModelServingActor[RECORD, MODEL_OUTPUT](label, modelFactory, makeDefaultModelOutput, defaultModelDescriptor))
}

/** Used as an Actor message. */
case class GetState(label: String)
