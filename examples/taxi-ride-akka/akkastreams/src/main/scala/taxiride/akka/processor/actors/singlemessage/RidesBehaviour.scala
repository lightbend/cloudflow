package taxiride.akka.processor.actors.singlemessage

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import taxiride.datamodel.TaxiRideOrFare.MessageType
import taxiride.datamodel._

import scala.collection.mutable.Map

// messages
case class ProcessMessage(reply: ActorRef[Option[TaxiRideFare]], record : TaxiRideOrFare)

// Actor
object RideShare{

  private val rideState: Map[Long, TaxiRide] = Map()
  private val fareState: Map[Long, TaxiFare] = Map()

  def apply(rideid: String): Behavior[ProcessMessage] = processMessage(rideid)

  private def processMessage(rideid: String): Behavior[ProcessMessage] =
     Behaviors.receive { (context, msg) => {
       msg.record.messageType match {
         case MessageType.Ride(ride) =>
           fareState.get(ride.rideId) match {
             case Some(fare) =>
               fareState -= ride.rideId
               msg.reply ! Some(TaxiRideFare(ride.rideId, fare.totalFare))
             case _ =>
               rideState += (ride.rideId -> ride)
               msg.reply ! None
           }
         case MessageType.Fare(fare) =>
           rideState.get(fare.rideId) match {
             case Some(ride) =>
               rideState -= fare.rideId
               msg.reply ! Some(TaxiRideFare(ride.rideId, fare.totalFare))
             case None =>
               fareState += (fare.rideId -> fare)
               msg.reply ! None
            }
          case MessageType.Empty =>
        }
        Behaviors.same
     }
  }
}