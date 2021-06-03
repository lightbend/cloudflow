package com.example

import cloudflow.flink._
import cloudflow.streamlets.StreamletShape

// TODO rename to ReportPrinter
class ReportPrinterStep0 extends FlinkStreamlet {
  // 1. TODO Create inlets and outlets
  // 2. TODO Define the shape of the streamlet
  val shape = StreamletShape.empty
  // 3. TODO Override createLogic to provide StreamletLogic
  def createLogic(): FlinkStreamletLogic = new FlinkStreamletLogic() {
    def buildExecutionGraph = ()
  }
}
