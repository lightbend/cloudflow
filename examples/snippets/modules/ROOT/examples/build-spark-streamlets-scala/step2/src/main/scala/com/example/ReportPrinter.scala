package com.example

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.spark._

object ReportPrinter extends SparkStreamlet {
  // 1. Create inlets and outlets
  val in = AvroInlet[Report]("report-in")

  // 2. Define the shape of the streamlet
  override val shape = StreamletShape.withInlets(in)

  // 3. TODO Override createLogic to provide StreamletLogic
  override def createLogic =  ???
}