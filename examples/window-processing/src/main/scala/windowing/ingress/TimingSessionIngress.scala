package windowing.ingress

import akka.NotUsed
import akka.stream.scaladsl.Source
import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto.ProtoOutlet
import window.datamodel.SimpleMessage

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

final case object TimingSessionIngress extends AkkaStreamlet {

  private val frequency: FiniteDuration = 100.millisecond // Message emit frequency
  private val submitter                 = TimedSessionDataSubmitter()

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

  private def nextRecord(): SimpleMessage =
    submitter.getNext()
}

case class TimedSessionDataSubmitter() {

  var value: Long = 0
  var timestamp   = System.currentTimeMillis()

  val eventdata    = new ListBuffer[SimpleMessage]
  var dataIterator = generateData()

  def getNext(): SimpleMessage = {
    if (!dataIterator.hasNext) {
      timestamp = timestamp + 2000
      Thread.sleep(2000)
      dataIterator = generateData()
    }
    dataIterator.next()
  }

  private def generateData(): Iterator[SimpleMessage] = {
    val windowLength = 5
    eventdata.clear()
    (0 until windowLength).foreach { _ ⇒
      eventdata += SimpleMessage(timestamp, value)
      timestamp = timestamp + 100
      value = value + 1
    }
    eventdata.iterator
  }
}
