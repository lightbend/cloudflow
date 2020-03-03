package com.example

import cloudflow.streamlets._
import cloudflow.streamlets.avro._

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._

object AtLeastOnceReportPrinater extends AkkaStreamlet {
  // 1. Create inlets and outlets
  val inlet = AvroInlet[Report]("report-in")

  // 2. Define the shape of the streamlet
  val shape = StreamletShape.withInlets(inlet)
  
  // 3. Override createLogic to provide StreamletLogic
  // tag::atLeastOnce[]
  def createLogic = new RunnableGraphStreamletLogic() {
    def format(report: Report) = s"${report.name}\n\n${report.description}"
    def runnableGraph =
      sourceWithOffsetContext(inlet)
        .map { report ⇒
          println(format(report))
          report
        }
        .to(committableSink)
  }
  // end::atLeastOnce[]
}