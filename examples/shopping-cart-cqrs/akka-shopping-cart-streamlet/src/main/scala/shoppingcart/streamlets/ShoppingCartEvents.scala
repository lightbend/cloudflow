package shoppingcart.streamlets

import java.util.{Date, UUID}

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.stream.scaladsl.{RunnableGraph, Source}
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.akkastream.{AkkaStreamlet, AkkaStreamletLogic}
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroOutlet
import shoppingcart.data.ShoppingCartEvent
import shoppingcart.streamlets.ShoppingCartGenerator.Command

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

class ShoppingCartEvents extends AkkaStreamlet {
  val out   = AvroOutlet[ShoppingCartEvent]("out")
  def shape(): StreamletShape = StreamletShape.withOutlets(out)


  def createLogic() = new RunnableGraphStreamletLogic() {
    val (queue, queueSource) = Source.queue[ShoppingCartEvent](10, OverflowStrategy.dropNew).preMaterialize()

    override def runnableGraph(): RunnableGraph[_] = queueSource
      .to(plainSink(out))

    Source.repeat("Test")
      .throttle(1, 5.seconds)
      .map(x => {
        val id = UUID.randomUUID().toString()
        system.spawn(ShoppingCartGenerator(queue, id), "cart-"+id)
        log.info("New Cart Actor Created: cart-"+id)
      }).run()
  }
}

object ShoppingCartGenerator {

  sealed trait Command
  private case object Tick extends Command

  def apply(queue: SourceQueueWithComplete[ShoppingCartEvent], cartId:String): Behavior[Command] = {
    Behaviors.withTimers(timers => new ShoppingCartGenerator(queue, cartId, timers).start())
  }
}

class ShoppingCartGenerator(queue: SourceQueueWithComplete[ShoppingCartEvent], cartId:String, timers: TimerScheduler[ShoppingCartGenerator.Command]) {

  def start(): Behavior[Command] =
    Behaviors.setup { context =>
      queue.offer(ShoppingCartEvent(cartId, "", 0, new Date().getTime(), "NewCart"))
      timers.startTimerAtFixedRate(ShoppingCartGenerator.Tick, 5.seconds)

      cart(cartId, Map[String, Int]())

    }

  def cart(cartId:String, shoppingCart: Map[String, Int]): Behavior[Command] = Behaviors.receiveMessage { message =>
    val event = randomEvent(cartId, shoppingCart)
    queue.offer(event)

    event.eventType match {
      case "ItemAdded" =>
        cart(cartId, shoppingCart + (event.itemId -> event.newQuantity))
      case "ItemRemoved" =>
        cart(cartId, shoppingCart - event.itemId)
      case "ItemQuantityAdjusted" =>
        cart(cartId, shoppingCart + (event.itemId -> event.newQuantity))
      case "CheckedOut" =>
        Behaviors.stopped
    }
  }

  def randomEvent(cartId: String, cart:Map[String, Int]):ShoppingCartEvent = {
    val eventType = EventType(Random.nextInt(4))

    eventType match {
      case "ItemAdded" =>
        val newItem = Items(Random.nextInt(4))
        val quantity = Random.nextInt(10)

        ShoppingCartEvent(cartId, newItem, quantity, new Date().getTime(), eventType)
      case "ItemRemoved" =>
        if(cart.nonEmpty) {
          val removeItem = cart.keys.toList(Random.nextInt(cart.keySet.size))
          ShoppingCartEvent(cartId, removeItem, 0, new Date().getTime(), eventType)
        } else
          randomEvent(cartId, cart)
      case "ItemQuantityAdjusted" =>
        if(cart.nonEmpty) {
          val item = cart.keys.toList(Random.nextInt(cart.keySet.size))
          val newQuantity = Random.nextInt(10)
          ShoppingCartEvent(cartId, item, newQuantity, new Date().getTime(), eventType)
        } else
          randomEvent(cartId, cart)
      case "CheckedOut" =>
        if(cart.nonEmpty)
          ShoppingCartEvent(cartId, "", 0, new Date().getTime(), eventType)
        else
          randomEvent(cartId, cart)
    }
  }

  val Items = List(
    "socks",
    "shirt",
    "shoes",
    "laptop"
  )

  val EventType = List(
    "ItemAdded",
    "ItemRemoved",
    "ItemQuantityAdjusted",
    "CheckedOut"
  )

}
