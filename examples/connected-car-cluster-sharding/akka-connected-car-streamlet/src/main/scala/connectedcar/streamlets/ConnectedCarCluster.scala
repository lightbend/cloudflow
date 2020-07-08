package connectedcar.streamlets

import akka.cluster.sharding.typed.scaladsl.{Entity, EntityTypeKey}
import akka.util.Timeout
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.stream.scaladsl.SourceWithContext
import cloudflow.akkastream.scaladsl.{FlowWithCommittableContext, RunnableGraphStreamletLogic}
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.{AvroInlet, AvroOutlet}
import connectedcar.actors.{ConnectedCarActor, ConnectedCarERecordWrapper}

import scala.concurrent.duration._
import cloudflow.akkastream.{AkkaStreamlet, Clustering}
import connectedcar.data.{ConnectedCarAgg, ConnectedCarERecord}

class ConnectedCarCluster extends AkkaStreamlet with Clustering {
  val in    = AvroInlet[ConnectedCarERecord]("in")
  val out   = AvroOutlet[ConnectedCarAgg]("out", m ⇒ m.driver.toString)
  val shape = StreamletShape(in).withOutlets(out)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val typeKey = EntityTypeKey[ConnectedCarERecordWrapper]("Car")

    val entity = Entity(typeKey)(createBehavior = entityContext => ConnectedCarActor(entityContext.entityId))

    val source:SourceWithContext[
      ConnectedCarERecord,
      CommittableOffset, _] = shardedSourceWithCommittableContext(in, entity)

    val sharding = clusterSharding()

    def runnableGraph = source.via(flow).to(committableSink(out))

    implicit val timeout: Timeout = 3.seconds
    def flow =
      FlowWithCommittableContext[ConnectedCarERecord]
        .mapAsync(5)(msg ⇒ {
            val carActor = sharding.entityRefFor(typeKey, msg.carId.toString)
            carActor.ask[ConnectedCarAgg](ref => ConnectedCarERecordWrapper(msg, ref))
          })
  }
}
