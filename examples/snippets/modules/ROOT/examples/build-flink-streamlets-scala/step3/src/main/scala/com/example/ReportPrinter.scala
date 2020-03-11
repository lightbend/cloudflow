package com.example

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.flink._

import org.apache.flink.streaming.api.scala._

class ReportPrinter extends FlinkStreamlet {
  // 1. Create inlets and outlets
  @transient val in = AvroInlet[Report]("report-in")

  // 2. Define the shape of the streamlet
  @transient val shape = StreamletShape.withInlets(in)

  // 3. Override createLogic to provide StreamletLogic, where the inlets and outlets are used to read and write streams.
  override def createLogic() = new FlinkStreamletLogic {
    def format(report: Report) = s"${report.name}\n\n${report.description}"

    override def buildExecutionGraph =
      readStream(in).map(r => format(r)).print()

  }
}
