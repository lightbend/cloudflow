package taxiride.akka.processor.actors.optimized

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import taxiride.datamodel.TaxiRideOrFare.MessageType
import taxiride.datamodel._


// messages
case class ProcessMessage(reply: ActorRef[Option[TaxiRideFare]], record : TaxiRideOrFare)

// Actor
object RideShare{

  def apply(rideid: String): Behavior[ProcessMessage] = {

    def executionstate(rideState: Map[Long, TaxiRide], fareState: Map[Long, TaxiFare]): Behavior[ProcessMessage] = {
      Behaviors.receive { (context, msg) => {
        msg.record.messageType match {
          case MessageType.Ride(ride) =>
            fareState.get(ride.rideId) match {
              case Some(fare) =>
                msg.reply ! Some(TaxiRideFare(ride.rideId, fare.totalFare))
                executionstate(rideState, fareState - ride.rideId)
              case _ =>
                msg.reply ! None
                executionstate(rideState + (ride.rideId -> ride), fareState)
            }

          case MessageType.Fare(fare) =>
            rideState.get(fare.rideId) match {
              case Some(ride) =>
                msg.reply ! Some(TaxiRideFare(ride.rideId, fare.totalFare))
                executionstate(rideState - fare.rideId, fareState)
              case None =>
                msg.reply ! None
                executionstate(rideState, fareState + (fare.rideId -> fare))
            }

          case MessageType.Empty =>
            executionstate(rideState, fareState)
        }
      }}
    }
    executionstate(Map(), Map())
  }
}