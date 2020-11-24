package taxiride.logger

import cloudflow.flink.{FlinkStreamlet, FlinkStreamletLogic}
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto.ProtoInlet
import org.apache.flink.streaming.api.scala._
import taxiride.datamodel.TaxiRideFare

class FarePerRideLogger extends FlinkStreamlet {

  val inlet = ProtoInlet[TaxiRideFare]("in")
  val shape = StreamletShape.withInlets(inlet)

  override protected def createLogic(): FlinkStreamletLogic = new FlinkStreamletLogic {
    override def buildExecutionGraph = {
      readStream(inlet)
        .map { taxiRideFare ⇒
          log.info(s"Ride - ${taxiRideFare.rideId}, Total fare - ${taxiRideFare.totalFare}")
          taxiRideFare
        }
    }
  }
}
