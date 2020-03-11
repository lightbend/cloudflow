package com.example

import cloudflow.streamlets.avro._
import cloudflow.flink._

class ReportPrinter extends FlinkStreamlet {
  // 1. Create inlets and outlets
  @transient val in = AvroInlet[Report]("report-in")

  // 2. TODO Define the shape of the streamlet
  val shape = ???
  // 3. TODO Override createLogic to provide StreamletLogic
  def createLogic(): FlinkStreamletLogic = ???
}
