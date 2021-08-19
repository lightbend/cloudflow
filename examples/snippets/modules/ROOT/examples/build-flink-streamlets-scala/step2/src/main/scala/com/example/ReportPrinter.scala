package com.example

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.streamlets.StreamletShape

import cloudflow.flink._
// TODO rename to ReportPrinter
class ReportPrinterStep2 extends FlinkStreamlet {
  // 1. Create inlets and outlets
  @transient val in = AvroInlet[Report]("report-in")

  // 2. Define the shape of the streamlet
  @transient val shape = StreamletShape.withInlets(in)

  // 3. TODO Override createLogic to provide StreamletLogic
  def createLogic(): FlinkStreamletLogic = new FlinkStreamletLogic() {
    def buildExecutionGraph = ()
  }
}
