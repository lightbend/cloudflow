package taxiride.akka.processor.streamlets.optimized.persistent

import akka.util.Timeout
import akka.cluster.sharding.typed.scaladsl._
import akka.persistence.typed.PersistenceId
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.proto._
import taxiride.akka.processor.streamlets.optimized.KafkaSupport
import taxiride.akka.processor.actors.optimized.persistent._
import taxiride.datamodel._

import scala.concurrent.duration._


class RidesProcessorStreamlet extends AkkaStreamlet with Clustering {

  val inTaxiMessage = ProtoInlet[TaxiRideOrFare]("in-taximessage")
  val out        = ProtoOutlet[TaxiRideFare]("out", _.rideId.toString)

  val shape = StreamletShape.withInlets(inTaxiMessage).withOutlets(out)

  override protected def createLogic(): AkkaStreamletLogic = new RunnableGraphStreamletLogic() {

    val topic = context.findTopicForPort(inTaxiMessage)
    KafkaSupport.numberOfPartitions(context.runtimeBootstrapServers(topic), topic)

    val typeKey = EntityTypeKey[TaxiRideMessage]("RideShare")

    val entity = Entity(typeKey)(createBehavior = entityContext =>
      RideShare(entityContext.entityId, PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)))

    val sharding = clusterSharding()

    def runnableGraph =
      shardedSourceWithCommittableContext(inTaxiMessage, entity).via(messageFlow).to(committableSink(out))

    implicit val timeout: Timeout = 3.seconds
    private def messageFlow =
      FlowWithCommittableContext[TaxiRideOrFare]
        .mapAsync(1)(msg ⇒ {
          val rideId = if(msg.messageType.isFare) msg.messageType.fare.get.rideId else msg.messageType.ride.get.rideId
          val actor = sharding.entityRefFor(typeKey, KafkaSupport.convertRideToPartition(rideId))
          actor.ask[Option[TaxiRideFare]](ref => TaxiRideMessage(ref, msg))
        }).collect{ case Some(v) => v }
  }
}