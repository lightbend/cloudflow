package com.example

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._

import cloudflow.akkastreamsdoc._

object SensorDataToMetrics extends AkkaStreamlet {
  val in                   = AvroInlet[Data]("in")
  val out                  = AvroOutlet[Data]("out")
  final override val shape = StreamletShape.withInlets(in).withOutlets(out)

  final override def createLogic = new RunnableGraphStreamletLogic {
    override final def runnableGraph =
      sourceWithOffsetContext(in)
        .map { i ⇒
          i
        }
        .to(committableSink(out))
  }
}
