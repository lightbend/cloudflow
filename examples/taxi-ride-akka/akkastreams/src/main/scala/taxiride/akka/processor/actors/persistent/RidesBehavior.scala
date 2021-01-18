package taxiride.akka.processor.actors.persistent

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed._
import akka.persistence.typed.scaladsl._
import cloudflow.akkastream.persistence.JacksonCborSerializable
import taxiride.datamodel.TaxiRideOrFare._
import taxiride.datamodel._

// Command
final case class TaxiRideMessage(reply: ActorRef[Option[TaxiRideFare]], record : TaxiRideOrFare) extends JacksonCborSerializable

// Event
final case class TaxiRideEvent(state : TaxiState) extends JacksonCborSerializable

// State
final case class TaxiState(rideState: Option[TaxiRide], fareState: Option[TaxiFare]) extends JacksonCborSerializable{
  def reset(updated: TaxiState): TaxiState = copy(updated.rideState, updated.fareState)
}

// Actor
object RideShare{

  // commandHandler defines how to handle command by producing Effects e.g. persisting events, stopping the persistent actor.
  private val commandHandler: (TaxiState, TaxiRideMessage) => Effect[TaxiRideEvent, TaxiState] = { (state, cmd) =>
    cmd match {
      case cmd: TaxiRideMessage => processTaxiMessage(state, cmd)
    }
  }

  private def processTaxiMessage(state: TaxiState, cmd: TaxiRideMessage): Effect[TaxiRideEvent, TaxiState] = {
    def processMessage(): (TaxiState, Option[TaxiRideFare]) =
      cmd.record.messageType match {
        // Ride message
        case MessageType.Ride(ride) =>
          state.fareState match {
            case Some(fare) =>
              // We have a fare with the same ride ID - produce result
              (TaxiState(state.rideState, None), Some(TaxiRideFare(ride.rideId, fare.totalFare)))
            case _ =>
              // Remeber the ride to be used when the fare with the same ride ID arrives
              (TaxiState(Some(ride), state.fareState), None)
          }
        // Fare message
        case MessageType.Fare(fare) =>
          state.rideState match {
            case Some(ride) =>
              // We have a ride with the same ride ID - produce result
              (TaxiState(None, state.fareState), Some(TaxiRideFare(ride.rideId, fare.totalFare)))
            case None =>
              // Remeber the fare to be used when the ride with the same ride ID arrives
              (TaxiState(state.rideState, Some(fare)), None)
          }
        // Unknown message type - ignore
        case MessageType.Empty =>
          (state, None)
      }

    // Calculate new State and reply
    val stateWithReply = processMessage()
    // Persist state and send reply
    Effect.persist(TaxiRideEvent(stateWithReply._1)).thenRun(state => cmd.reply ! stateWithReply._2)
  }

  // eventHandler returns the new state given the current state when an event has been persisted.
  private val eventHandler: (TaxiState, TaxiRideEvent) => TaxiState = {
    println(s"IN event handler")
    (state, evt) => state.reset(evt.state)
  }

  def apply(entityId: String, persistenceId: PersistenceId): Behavior[TaxiRideMessage] = {
    Behaviors.setup { context =>
      context.log.info(s"Starting ride share $entityId, with persistenceID $persistenceId")
      EventSourcedBehavior(persistenceId, emptyState = TaxiState(None, None), commandHandler, eventHandler)
        // Set up snapshotting
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 3))
    }
  }
}