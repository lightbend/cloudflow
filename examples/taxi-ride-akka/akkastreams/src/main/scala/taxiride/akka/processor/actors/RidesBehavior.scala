package taxiride.akka.processor.actors

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import taxiride.datamodel.TaxiRideOrFare._
import taxiride.datamodel._

// messages
case class ProcessMessage(reply: ActorRef[Option[TaxiRideFare]], record : TaxiRideOrFare)

// Actor
object RideShare{

  def apply(rideid: String): Behavior[ProcessMessage] = {

    // Execute behavior with the current state
    def executionstate(rideState: Option[TaxiRide], fareState: Option[TaxiFare]): Behavior[ProcessMessage] = {
      // Behaviour describes processing of the actor
      Behaviors.receive { (context, msg) => {
        msg.record.messageType match {
          // Ride message
          case MessageType.Ride(ride) =>
            fareState match {
              case Some(fare) =>
                // We have a fare with the same ride ID - produce result
                msg.reply ! Some(TaxiRideFare(ride.rideId, fare.totalFare))
                executionstate(rideState, None)
              case _ =>
                // Remeber the ride to be used when the fare with the same ride ID arrives
                msg.reply ! None
                executionstate(Some(ride), fareState)
            }

          // Fare message
          case MessageType.Fare(fare) =>
            rideState match {
              case Some(ride) =>
                // We have a ride with the same ride ID - produce result
                msg.reply ! Some(TaxiRideFare(ride.rideId, fare.totalFare))
                executionstate(None, fareState)
              case None =>
                // Remeber the fare to be used when the ride with the same ride ID arrives
                msg.reply ! None
                executionstate(rideState, Some(fare))
            }

          // Unknown message type - ignore
          case MessageType.Empty =>
            executionstate(rideState, fareState)
        }
      }}
    }
    // Initialize state
    executionstate(None, None)
  }
}