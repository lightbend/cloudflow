package connectedcar.streamlets

import akka.stream.scaladsl.RunnableGraph
import cloudflow.akkastream.{ AkkaStreamlet, AkkaStreamletLogic }
import cloudflow.akkastream.scaladsl.{ FlowWithCommittableContext, RunnableGraphStreamletLogic }
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto.ProtoInlet
import connectedcar.data.ConnectedCarAgg

object CarDataPrinter extends AkkaStreamlet {
  val in: ProtoInlet[ConnectedCarAgg] = ProtoInlet[ConnectedCarAgg]("in")
  val shape: StreamletShape           = StreamletShape(in)

  override def createLogic(): AkkaStreamletLogic = new RunnableGraphStreamletLogic() {
    private val flow = FlowWithCommittableContext[ConnectedCarAgg]
      .map { record ⇒
        log.info("CarId: " + record.carId)
      }

    def runnableGraph: RunnableGraph[_] =
      sourceWithCommittableContext(in).via(flow).to(committableSink)
  }
}
