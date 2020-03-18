package com.example

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.flink._

import org.apache.flink.streaming.api.scala._

class ReportIngress extends FlinkStreamlet {
  // 1. Create inlets and outlets
  @transient val out = AvroOutlet[Report]("out", _.id)

  // 2. Define the shape of the streamlet
  @transient val shape = StreamletShape.withOutlets(out)

  // 3. Override createLogic to provide StreamletLogic, where the inlets and outlets are used to read and write streams.
  override def createLogic() = new FlinkStreamletLogic {
    override def buildExecutionGraph =
      writeStream(out, context.env.fromElements(Report("abc", "test", "Just a test", List("ab", "bc"))))

  }
}
