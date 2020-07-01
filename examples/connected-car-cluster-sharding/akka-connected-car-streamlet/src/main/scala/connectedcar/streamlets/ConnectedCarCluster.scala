package connectedcar.streamlets

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.util.Timeout
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.external.ExternalShardAllocationStrategy
import akka.cluster.sharding.typed.ClusterShardingSettings
import cloudflow.akkastream.scaladsl.{FlowWithCommittableContext, RunnableGraphStreamletLogic}
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.{AvroInlet, AvroOutlet}
import connectedcar.actors.{ConnectedCarActor, ConnectedCarERecordWrapper}

import scala.concurrent.duration._
import akka.pattern.ask
import akka.stream.scaladsl.Sink
import cloudflow.akkastream.{AkkaStreamlet, Clustering}
import connectedcar.data.{ConnectedCarAgg, ConnectedCarERecord}

object ConnectedCarCluster extends AkkaStreamlet with Clustering {
  val in    = AvroInlet[ConnectedCarERecord]("in")
  val out   = AvroOutlet[ConnectedCarAgg]("out", m ⇒ m.driver.toString)
  val shape = StreamletShape(in).withOutlets(out)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val groupId = "user-topic-group-id"
    val typeKey = EntityTypeKey[ConnectedCarERecordWrapper](groupId)

    val source = shardedSourceWithCommittableContext(in,
                                      typeKey: EntityTypeKey[ConnectedCarERecordWrapper],
                                      (msg: ConnectedCarERecordWrapper) => msg.record.carId+""
                                    )

    def runnableGraph = source.map(kafkaEnvelope => {
      clusterSharding.init(
        Entity(typeKey)(createBehavior = _ => ConnectedCarActor())
          .withAllocationStrategy(new ExternalShardAllocationStrategy(system, typeKey.name))
          .withMessageExtractor(kafkaEnvelope.kafkaShardingExtractor)
          .withSettings(ClusterShardingSettings(system.toTyped)))

      kafkaEnvelope.source
        .via(flow)
        .to(committableSink(out))
        .run()
    }).to(Sink.ignore)

    val clusterSharding = ClusterSharding(system.toTyped)

    implicit val timeout: Timeout = 3.seconds
    def flow =
      FlowWithCommittableContext[ConnectedCarERecord]
        .mapAsync(5) {
          msg ⇒ ({
            val carActor = clusterSharding.entityRefFor(typeKey, "counter-1")
            carActor.ask[ConnectedCarAgg](ref => ConnectedCarERecordWrapper(msg, ref))
          })
        }
  }
}
