package com.example

import akka.stream.scaladsl.Sink

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.streamlets.StreamletShape

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._

//TODO rename to ReportPrinter
object ReportPrinterStep0 extends AkkaStreamlet {
  // 1. TODO Create inlets and outlets
  // 2. TODO Define the shape of the streamlet
  val shape = StreamletShape.empty
  // 3. TODO Override createLogic to provide StreamletLogic
  def createLogic = new AkkaStreamletLogic() { def run = () }
}
