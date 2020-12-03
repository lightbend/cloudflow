package taxiride.akka.processor.actors

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import taxiride.datamodel._

import scala.collection.mutable.Map

// Controller
trait RidesProcessingActor

// messages
case class ProcessFare(reply: ActorRef[Option[TaxiRideFare]], fare : TaxiFare) extends RidesProcessingActor
case class ProcessRide(reply: ActorRef[Option[TaxiRideFare]], ride : TaxiRide) extends RidesProcessingActor

// Actor
object RideShare{

  private val rideState: Map[Long, TaxiRide] = Map()
  private val fareState: Map[Long, TaxiFare] = Map()

  def apply(rideid: String): Behavior[RidesProcessingActor] = processMessage(rideid)

  private def processMessage(rideid: String): Behavior[RidesProcessingActor] =
     Behaviors.receive { (context, msg) => {
//       context.log.info(s"a new message $msg for ride $rideid")
        msg match {
          case ride: ProcessRide =>
            fareState.get(ride.ride.rideId) match {
              case Some(fare) =>
                fareState -= ride.ride.rideId
                ride.reply ! Some(TaxiRideFare(ride.ride.rideId, fare.totalFare))
              case _ =>
                rideState += (ride.ride.rideId -> ride.ride)
                ride.reply ! None
            }

          case fare: ProcessFare =>
            rideState.get(fare.fare.rideId) match {
              case Some(ride) =>
                rideState -= fare.fare.rideId
                fare.reply ! Some(TaxiRideFare(ride.rideId, fare.fare.totalFare))
              case None =>
                fareState += (fare.fare.rideId -> fare.fare)
                fare.reply ! None
            }
        }
        Behaviors.same
     }
  }
}