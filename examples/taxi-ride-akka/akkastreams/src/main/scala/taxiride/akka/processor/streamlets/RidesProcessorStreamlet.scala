package taxiride.akka.processor.streamlets

import akka.util.Timeout
import akka.cluster.sharding.typed.scaladsl._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.proto._
import cloudflow.akkastream.util.scaladsl._
import taxiride.akka.processor.actors._
import taxiride.datamodel._

import scala.concurrent.duration._


class RidesProcessorStreamlet extends AkkaStreamlet with Clustering {

  val inTaxiRide = ProtoInlet[TaxiRide]("in-taxiride")
  val inTaxiFare = ProtoInlet[TaxiFare]("in-taxifare")
  val out        = ProtoOutlet[TaxiRideFare]("out", _.rideId.toString)

  val shape = StreamletShape.withInlets(inTaxiRide, inTaxiFare).withOutlets(out)

  implicit val timeout: Timeout = 3.seconds

  override protected def createLogic(): AkkaStreamletLogic = new RunnableGraphStreamletLogic() {

    val typeKey = EntityTypeKey[RidesProcessingActor]("RideShare")

    val entity = Entity(typeKey)(createBehavior = entityContext => RideShare(entityContext.entityId))

    val sharding = clusterSharding()

    def runnableGraph = {
      val rides = shardedSourceWithCommittableContext(inTaxiRide, entity).via(ridesFlow)

      val fares = shardedSourceWithCommittableContext(inTaxiFare, entity).via(faresFlow)

      Merger.source(Seq(rides, fares)).to(committableSink(out))
    }

    private def ridesFlow =
      FlowWithCommittableContext[TaxiRide]
        .mapAsync(1)(msg ⇒ {
          val actor = sharding.entityRefFor(typeKey, msg.rideId.toString)
          actor.ask[Option[TaxiRideFare]](ref => ProcessRide(ref, msg))
        }).collect{ case Some(v) => v }
    private def faresFlow =
      FlowWithCommittableContext[TaxiFare]
        .mapAsync(1)(msg ⇒ {
          val actor = sharding.entityRefFor(typeKey, msg.rideId.toString)
          actor.ask[Option[TaxiRideFare]](ref => ProcessFare(ref, msg))
        }).collect{ case Some(v) => v }
  }
}
