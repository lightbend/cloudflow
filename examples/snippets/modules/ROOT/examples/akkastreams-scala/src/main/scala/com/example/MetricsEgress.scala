package com.example

import cloudflow.streamlets._
import cloudflow.akkastream._
import cloudflow.streamlets.avro._
import cloudflow.akkastream.scaladsl._

import akka.kafka.scaladsl.Committer

import cloudflow.akkastreamsdoc.Data

object MetricsEgress extends AkkaStreamlet {
  val in                   = AvroInlet[Data]("in")
  final override val shape = StreamletShape.withInlets(in)

  final override def createLogic = new RunnableGraphStreamletLogic {
    override final def runnableGraph =
      sourceWithOffsetContext(in)
        .map { i ⇒
          println(s"Int: ${i.value}"); i
        }
        .to(Committer.sinkWithOffsetContext(defaultCommitterSettings))
  }
}
