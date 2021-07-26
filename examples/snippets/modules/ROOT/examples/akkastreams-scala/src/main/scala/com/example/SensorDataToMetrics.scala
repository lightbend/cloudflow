package com.example

import akka.stream.scaladsl.RunnableGraph
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastreamsdoc._

object SensorDataToMetrics extends AkkaStreamlet {
  val in: CodecInlet[Data]           = AvroInlet[Data]("in")
  val out: CodecOutlet[Data]         = AvroOutlet[Data]("out")
  override val shape: StreamletShape = StreamletShape.withInlets(in).withOutlets(out)

  override def createLogic(): AkkaStreamletLogic = new RunnableGraphStreamletLogic {
    override final def runnableGraph: RunnableGraph[_] =
      sourceWithCommittableContext(in)
        .map(i => i)
        .to(committableSink(out))
  }
}
