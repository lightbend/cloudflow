package taxiride.akka.processor.merger

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.proto._
import cloudflow.akkastream.util.scaladsl._
import taxiride.datamodel.TaxiRideOrFare._
import taxiride.datamodel._


class MessageMerger extends AkkaStreamlet {

  val inTaxiRide = ProtoInlet[TaxiRide]("in-taxiride")
  val inTaxiFare = ProtoInlet[TaxiFare]("in-taxifare")
  val out        = ProtoOutlet[TaxiRideOrFare]("taxirideorfare", _.rideId.toString)

  val shape = StreamletShape.withInlets(inTaxiRide, inTaxiFare).withOutlets(out)

  override protected def createLogic(): AkkaStreamletLogic = new RunnableGraphStreamletLogic() {

    def runnableGraph = {

      Merger.source(Seq(
        sourceWithCommittableContext(inTaxiFare).map(f => TaxiRideOrFare(rideId = f.rideId, messageType = MessageType.Fare(f))),
        sourceWithCommittableContext(inTaxiRide).map(r => TaxiRideOrFare(rideId = r.rideId, messageType = MessageType.Ride(r)))
      )).to(committableSink(out))
    }
  }
}
