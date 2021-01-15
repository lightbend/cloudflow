package taxiride.akka.processor.streamlets

import akka.util.Timeout
import akka.cluster.sharding.typed.scaladsl._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.proto._
import taxiride.akka.processor.actors._
import taxiride.datamodel._

import scala.concurrent.duration._


class RidesProcessorStreamlet extends AkkaStreamlet with Clustering {

  val inTaxiMessage = ProtoInlet[TaxiRideOrFare]("in-taximessage")
  val out        = ProtoOutlet[TaxiRideFare]("out", _.rideId.toString)

  val shape = StreamletShape.withInlets(inTaxiMessage).withOutlets(out)

  implicit val timeout: Timeout = 3.seconds

  override protected def createLogic(): AkkaStreamletLogic = new RunnableGraphStreamletLogic() {

    val typeKey = EntityTypeKey[ProcessMessage]("RideShare")

    val entity = Entity(typeKey)(createBehavior = entityContext => RideShare(entityContext.entityId))

    val sharding = clusterSharding()

    def runnableGraph = {
      shardedSourceWithCommittableContext(inTaxiMessage, entity).via(messageFlow).to(committableSink(out))
    }

    private def messageFlow =
      FlowWithCommittableContext[TaxiRideOrFare]
        .mapAsync(1)(msg ⇒ {
          val actor = sharding.entityRefFor(typeKey, msg.rideId.toString)
          actor.ask[Option[TaxiRideFare]](ref => ProcessMessage(ref, msg))
        }).collect{ case Some(v) => v }
  }
}
