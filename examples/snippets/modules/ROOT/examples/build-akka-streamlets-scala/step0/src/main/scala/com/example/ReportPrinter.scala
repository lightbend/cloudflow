package com.example

import cloudflow.akkastream._
import cloudflow.streamlets.StreamletShape

//TODO rename to ReportPrinter
object ReportPrinterStep0 extends AkkaStreamlet {
  // 1. TODO Create inlets and outlets
  // 2. TODO Define the shape of the streamlet
  override val shape: StreamletShape = StreamletShape.empty
  // 3. TODO Override createLogic to provide StreamletLogic
  override def createLogic: AkkaStreamletLogic = new AkkaStreamletLogic() { override def run: Unit = () }
}
