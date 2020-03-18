package com.example

import cloudflow.flink._

class ReportPrinter extends FlinkStreamlet {
  // 1. TODO Create inlets and outlets
  // 2. TODO Define the shape of the streamlet
  val shape = ???
  // 3. TODO Override createLogic to provide StreamletLogic
  def createLogic(): FlinkStreamletLogic = ???
}
