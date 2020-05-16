package connectedcar.streamlets

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.util.Timeout
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.external.ExternalShardAllocationStrategy
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.kafka.ConsumerSettings
import akka.kafka.cluster.sharding.KafkaClusterSharding
import cloudflow.akkastream.scaladsl.{FlowWithCommittableContext, RunnableGraphStreamletLogic}
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.{AvroInlet, AvroOutlet}
import connectedcar.actors.ConnectedCarActor

import scala.concurrent.duration._
import akka.pattern.ask
import cloudflow.akkastream.{AkkaStreamlet, Clustering}
import connectedcar.data.{ConnectedCarAgg, ConnectedCarERecord}
import org.apache.kafka.common.serialization.StringDeserializer

object ConnectedCarCluster extends AkkaStreamlet with Clustering {
  val in    = AvroInlet[ConnectedCarERecord]("in")
  val out   = AvroOutlet[ConnectedCarAgg]("out", m ⇒ m.driver.toString)
  val shape = StreamletShape(in).withOutlets(out)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val groupId = "user-topic-group-id"
    val typeKey = EntityTypeKey[ConnectedCarERecord](groupId)

    val (source, messageExtractor) = shardedSourceWithCommittableContext(in,
                                      typeKey,
                                      (msg: ConnectedCarERecord) => msg.carId+""
                                    )

    def runnableGraph = source.via(flow).to(committableSink(out))

    messageExtractor.map(messageExtractor => {
      ClusterSharding(system.toTyped).init(
        Entity(typeKey)(createBehavior = _ => userBehaviour())
          .withAllocationStrategy(new ExternalShardAllocationStrategy(system, typeKey.name))
          .withMessageExtractor(messageExtractor)
          .withSettings(ClusterShardingSettings(system.toTyped)))
    })

    val carRegion: ActorRef = ClusterSharding(context.system).start(
      typeName = "Counter",
      entityProps = Props[ConnectedCarActor],
      settings = ClusterShardingSettings(context.system),
      extractEntityId = ConnectedCarActor.extractEntityId,
      extractShardId = ConnectedCarActor.extractShardId
    )

    implicit val timeout: Timeout = 3.seconds
    def flow =
      FlowWithCommittableContext[ConnectedCarERecord]
        .mapAsync(5)(msg ⇒ (carRegion ? msg).mapTo[ConnectedCarAgg])
  }
}
