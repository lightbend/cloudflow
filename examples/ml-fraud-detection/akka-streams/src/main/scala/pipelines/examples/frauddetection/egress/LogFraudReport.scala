package pipelines.examples.frauddetection.egress

import akka.stream.scaladsl.{ Flow, RunnableGraph, Sink }
import cloudflow.akkastream.scaladsl.{ FlowWithOffsetContext, RunnableGraphStreamletLogic }
import cloudflow.akkastream.{ AkkaStreamlet, StreamletLogic }
import pipelines.examples.frauddetection.data.FraudReport
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroInlet

class LogFraudReport extends AkkaStreamlet {

  //\\//\\//\\ INLETS //\\//\\//\\
  val fromTheFraudReport = AvroInlet[FraudReport]("in")

  //\\//\\//\\ OUTLETS //\\//\\//\\

  //\\//\\//\\ SHAPE //\\//\\//\\
  final override val shape = StreamletShape(fromTheFraudReport)

  //\\//\\//\\ LOGIC //\\//\\//\\
  final override def createLogic(): StreamletLogic = new RunnableGraphStreamletLogic() {

    def logging =
      Flow[FraudReport]
        .map { report ⇒ system.log.info(s"$report") }

    override def runnableGraph(): RunnableGraph[_] =
      plainSource(fromTheFraudReport).via(logging).to(Sink.ignore)
  }
}
