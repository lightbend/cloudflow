package shoppingcart.streamlets

import java.util.UUID

import akka.Done
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, ShardedDaemonProcess}
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardedDaemonProcessSettings}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.projection.{ProjectionBehavior, ProjectionContext, ProjectionId}
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.AtLeastOnceFlowProjection
import akka.stream.scaladsl.{FlowWithContext, Sink, Source}
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroOutlet
import akka.persistence.query.Offset
import akka.util.Timeout

import scala.concurrent.duration._
import cloudflow.akkastream.{AkkaStreamlet, Clustering}
import sample.cqrs.ShoppingCart.{CheckedOut, ItemAdded, ItemQuantityAdjusted, ItemRemoved}
import sample.cqrs.ShoppingCart
import shoppingcart.cqrs.{EventProcessorSettings, Main}
import shoppingcart.data.ShoppingCartEvent

class ShoppingCartStreamlet extends AkkaStreamlet with Clustering {
  val out   = AvroOutlet[ShoppingCartEvent]("out")
  val shape = StreamletShape.withOutlets(out)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val settings = EventProcessorSettings(system.toTyped)
    ShoppingCart.init(system.toTyped, settings)
    val sharding = ClusterSharding(system.toTyped)

    Main.startNode(system.toTyped)

    val shardedDaemonProcessSettings =
      ShardedDaemonProcessSettings(system.toTyped)

    ShardedDaemonProcess(system.toTyped).init(
      name = "ShoppingCartProjection",
      settings.parallelism,
      n => ProjectionBehavior(createProjectionFor(system.toTyped, settings, n)),
      shardedDaemonProcessSettings,
      Some(ProjectionBehavior.Stop))

    implicit val timeout:Timeout = 5.seconds

    def runnableGraph = Source.repeat(UUID.randomUUID().toString)
      .throttle(1, 2.second)
      .mapAsync(1)(id => {
        val entityRef =
          sharding.entityRefFor(ShoppingCart.EntityKey, id)
        entityRef.ask[ShoppingCart.Confirmation](ref => ShoppingCart.AddItem("socks",1, ref))
          .map(x=> {
            entityRef.ask[ShoppingCart.Confirmation](ref => ShoppingCart.AdjustItemQuantity("socks", 10, ref))
          })
      })
      .to(Sink.ignore)

    def createProjectionFor(
                             system: ActorSystem[_],
                             settings: EventProcessorSettings,
                             index: Int
                           ): AtLeastOnceFlowProjection[Offset, EventEnvelope[ShoppingCart.Event]] = {
      val tag = s"${settings.tagPrefix}-$index"
      val sourceProvider = EventSourcedProvider.eventsByTag[ShoppingCart.Event](
        system = system,
        readJournalPluginId = CassandraReadJournal.Identifier,
        tag = tag)

      val flow = FlowWithContext[EventEnvelope[ShoppingCart.Event], ProjectionContext]
        .map(x => {
          println("Event source message: "+x)
          x
        })
        .map( x =>
          x.event match {
            case e: ItemAdded => ShoppingCartEvent(e.cartId, e.itemId, e.quantity, 0, "ItemAdded")
            case e: ItemRemoved => ShoppingCartEvent(e.cartId, e.itemId, 0, 0, "ItemRemoved")
            case e: ItemQuantityAdjusted => ShoppingCartEvent(e.cartId, e.itemId, e.newQuantity, 0, "ItemQuantityAdjusted")
            case e: CheckedOut => ShoppingCartEvent(e.cartId, "", 0, System.currentTimeMillis(), "CheckedOut")
            case _ => ShoppingCartEvent("", "", 0, 0, "Error")
          })
        .mapAsync(1) { x =>
          sinkRef(out).write(x)
        }
        .map(x => Done)

      CassandraProjection.atLeastOnceFlow(
        projectionId = ProjectionId("shopping-carts", tag),
        sourceProvider,
        handler = flow)
    }
  }
}
