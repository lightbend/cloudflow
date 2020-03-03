package com.example

import akka.stream.scaladsl.Sink

import cloudflow.streamlets._
import cloudflow.streamlets.avro._

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._

object ReportPrinter extends AkkaStreamlet {
  // 1. Create inlets and outlets
  val inlet = AvroInlet[Report]("report-in")

  // 2. Define the shape of the streamlet
  val shape = StreamletShape.withInlets(inlet)
  
  // 3. Override createLogic to provide StreamletLogic
  def createLogic = new RunnableGraphStreamletLogic() {
    def format(report: Report) = s"${report.name}\n]n${report.description}"
    def runnableGraph =
      plainSource(inlet)
        .to(Sink.foreach(report ⇒ println(format(report))))
  }
}