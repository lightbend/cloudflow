package shoppingcart.streamlets

import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.{FlowWithCommittableContext, RunnableGraphStreamletLogic}
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroInlet
import shoppingcart.data.ShoppingCartEvent

object ShoppingCartPrinter extends AkkaStreamlet {
  val in    = AvroInlet[ShoppingCartEvent]("in")
  val shape = StreamletShape(in)

  override def createLogic() = new RunnableGraphStreamletLogic() {
    val flow = FlowWithCommittableContext[ShoppingCartEvent]
      .map { record ⇒
        log.info("CartId: " + record.cartId+" EventType: "+record.eventTime)
        println("CartId: " + record.cartId+" EventType: "+record.eventTime)
      }

    def runnableGraph =
      sourceWithCommittableContext(in).via(flow).to(committableSink)
  }
}
