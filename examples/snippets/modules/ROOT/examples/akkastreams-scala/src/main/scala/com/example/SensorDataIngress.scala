package com.example

import akka.stream.scaladsl._

import cloudflow.streamlets._
import cloudflow.akkastream._
import cloudflow.streamlets.avro._
import cloudflow.akkastream.scaladsl._

import scala.concurrent.duration._

import cloudflow.akkastreamsdoc.Data

object SensorDataIngress extends AkkaStreamlet {
  val out                  = AvroOutlet[Data]("out")
  override final val shape = StreamletShape.withOutlets(out)

  final override def createLogic = new RunnableGraphStreamletLogic {
    override final def runnableGraph =
      Source.tick(0 seconds, 10 millisecond, Data("test", 2)).to(plainSink(out))
  }
}
