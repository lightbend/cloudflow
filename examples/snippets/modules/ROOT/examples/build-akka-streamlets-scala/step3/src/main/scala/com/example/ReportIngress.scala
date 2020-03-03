package com.example

import akka.stream.scaladsl.Source

import cloudflow.streamlets._
import cloudflow.streamlets.avro._

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import scala.concurrent.duration._

object ReportIngress extends AkkaStreamlet {
  // 1. Create inlets and outlets
  val outlet = AvroOutlet[Report]("out",_.id)

  // 2. Define the shape of the streamlet
  val shape = StreamletShape.withOutlets(outlet)
  
  // 3. TODO Override createLogic to provide StreamletLogic
  def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph =
      Source.tick(0.seconds, 2.seconds, Report("abc", "test", "Just a test", List("ab", "bc"))).to(plainSink(outlet))
  }
}