package windowing.ingress

import akka.NotUsed
import akka.stream.scaladsl.Source
import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto.ProtoOutlet
import window.datamodel.SimpleMessage

import scala.concurrent.duration._

final case object SimpleIngress extends AkkaStreamlet {

  private val frequency: FiniteDuration = 500.millisecond // Message emit frequency
  private val frequencyMS               = frequency.toMillis
  private var value: Long               = -1
  private var time                      = System.currentTimeMillis()

  val out = ProtoOutlet[SimpleMessage]("out")

  final override val shape = StreamletShape(out)

  override final def createLogic = new RunnableGraphStreamletLogic {

    def runnableGraph =
      makeSource()
        .to(plainSink(out))
  }

  private def makeSource(): Source[SimpleMessage, NotUsed] =
    Source
      .repeat(NotUsed)
      .map(_ ⇒ nextRecord())
      .throttle(1, frequency)

  private def nextRecord(): SimpleMessage = {
    value = value + 1
    time = time + frequencyMS
    SimpleMessage(time, value)
  }
}
