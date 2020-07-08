package connectedcar.streamlets

import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.{ FlowWithCommittableContext, RunnableGraphStreamletLogic }
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroInlet
import connectedcar.data.ConnectedCarAgg

object CarDataPrinter extends AkkaStreamlet {
  val in    = AvroInlet[ConnectedCarAgg]("in")
  val shape = StreamletShape(in)

  override def createLogic() = new RunnableGraphStreamletLogic() {
    val flow = FlowWithCommittableContext[ConnectedCarAgg]
      .map { record ⇒
        log.info("CarId: " + record.carId)
      }

    def runnableGraph =
      sourceWithCommittableContext(in).via(flow).to(committableSink)
  }
}
