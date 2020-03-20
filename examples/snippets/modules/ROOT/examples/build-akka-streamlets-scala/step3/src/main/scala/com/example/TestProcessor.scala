package com.example

import cloudflow.akkastream._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic

import akka.stream.scaladsl.Flow

//tag::processor[]
class TestProcessor extends AkkaStreamlet {
  val in                   = AvroInlet[Data]("in")
  val out                  = AvroOutlet[Data]("out", _.id.toString)
  final override val shape = StreamletShape.withInlets(in).withOutlets(out)

  val flow = Flow[Data].filter(_.id % 2 == 0)
  override final def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = plainSource(in).via(flow).to(plainSink(out))
  }
}
//end::processor[]
