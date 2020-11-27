package connectedcar.streamlets

import akka.cluster.sharding.typed.scaladsl.{ Entity, EntityTypeKey }
import akka.util.Timeout
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.stream.scaladsl.{ RunnableGraph, SourceWithContext }
import cloudflow.akkastream.scaladsl.{ FlowWithCommittableContext, RunnableGraphStreamletLogic }
import cloudflow.streamlets.StreamletShape
import connectedcar.actors.{ ConnectedCarActor, ConnectedCarERecordWrapper }

import scala.concurrent.duration._
import cloudflow.akkastream.{ AkkaStreamlet, AkkaStreamletLogic, Clustering }
import cloudflow.streamlets.proto.{ ProtoInlet, ProtoOutlet }
import connectedcar.data.{ ConnectedCarAgg, ConnectedCarERecord }

class ConnectedCarCluster extends AkkaStreamlet with Clustering {
  val in: ProtoInlet[ConnectedCarERecord] = ProtoInlet[ConnectedCarERecord]("in")
  val out: ProtoOutlet[ConnectedCarAgg]   = ProtoOutlet[ConnectedCarAgg]("out", m ⇒ m.driver.toString)
  val shape: StreamletShape               = StreamletShape(in).withOutlets(out)

  override def createLogic: AkkaStreamletLogic = new RunnableGraphStreamletLogic() {
    private val typeKey = EntityTypeKey[ConnectedCarERecordWrapper]("Car")

    private val entity = Entity(typeKey)(createBehavior = entityContext => ConnectedCarActor(entityContext.entityId))

    private val source: SourceWithContext[ConnectedCarERecord, CommittableOffset, _] = shardedSourceWithCommittableContext(in, entity)

    private val sharding = clusterSharding()

    def runnableGraph: RunnableGraph[_] = source.via(flow).to(committableSink(out))

    private implicit val timeout: Timeout = 3.seconds
    private def flow =
      FlowWithCommittableContext[ConnectedCarERecord]
        .mapAsync(5) { msg ⇒
          val carActor = sharding.entityRefFor(typeKey, msg.carId.toString)
          carActor.ask[ConnectedCarAgg](ref => ConnectedCarERecordWrapper(msg, ref))
        }
  }
}
