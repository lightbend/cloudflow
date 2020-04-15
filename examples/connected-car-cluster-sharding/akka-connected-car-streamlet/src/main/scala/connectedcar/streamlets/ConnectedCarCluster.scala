package connectedcar.streamlets

import akka.actor.{ ActorRef, Props }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings }
import akka.util.Timeout
import cloudflow.akkastream.scaladsl.{ FlowWithCommittableContext, RunnableGraphStreamletLogic }
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.{ AvroInlet, AvroOutlet }
import connectedcar.actors.ConnectedCarActor

import scala.concurrent.duration._
import akka.pattern.ask
import cloudflow.akkastream.{ AkkaStreamlet, Clustering }
import connectedcar.data.{ ConnectedCarAgg, ConnectedCarERecord }

object ConnectedCarCluster extends AkkaStreamlet with Clustering {
  val in = AvroInlet[ConnectedCarERecord]("in")
  val out = AvroOutlet[ConnectedCarAgg]("out", m ⇒ m.driver.toString)
  val shape = StreamletShape(in).withOutlets(out)

  override def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = sourceWithOffsetContext(in).via(flow).to(committableSink(out))

    val carRegion: ActorRef = ClusterSharding(context.system).start(
      typeName = "Counter",
      entityProps = Props[ConnectedCarActor],
      settings = ClusterShardingSettings(context.system),
      extractEntityId = ConnectedCarActor.extractEntityId,
      extractShardId = ConnectedCarActor.extractShardId)

    implicit val timeout: Timeout = 3.seconds
    def flow = FlowWithCommittableContext[ConnectedCarERecord]
      .mapAsync(5)(msg ⇒ (carRegion ? msg).mapTo[ConnectedCarAgg])
  }
}
