package com.example

import cloudflow.streamlets.avro._
import cloudflow.streamlets.StreamletShape
import cloudflow.flink._

// TODO rename to ReportPrinter
class ReportPrinterStep1 extends FlinkStreamlet {
  // 1. Create inlets and outlets
  @transient val in = AvroInlet[Report]("report-in")

  // 2. TODO Define the shape of the streamlet
  val shape = StreamletShape.empty
  // 3. TODO Override createLogic to provide StreamletLogic
  def createLogic(): FlinkStreamletLogic = new FlinkStreamletLogic() {
    def buildExecutionGraph = ()
  }
}
