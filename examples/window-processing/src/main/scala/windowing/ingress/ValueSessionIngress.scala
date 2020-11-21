package windowing.ingress

import akka.NotUsed
import akka.stream.scaladsl.Source
import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto.ProtoOutlet
import window.datamodel.SimpleMessageSession

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

final case object ValueSessionIngress extends AkkaStreamlet {

  private val frequency: FiniteDuration = 100.millisecond // Message emit frequency
  private val submitter                 = ValueSessionDataSubmitter()

  val out = ProtoOutlet[SimpleMessageSession]("out")

  final override val shape = StreamletShape(out)

  override final def createLogic = new RunnableGraphStreamletLogic {

    def runnableGraph =
      makeSource()
        .to(plainSink(out))
  }

  private def makeSource(): Source[SimpleMessageSession, NotUsed] =
    Source
      .repeat(NotUsed)
      .map(_ ⇒ nextRecord())
      .throttle(1, frequency)

  private def nextRecord(): SimpleMessageSession =
    submitter.getNext()
}

case class ValueSessionDataSubmitter() {

  var value: Long = 0
  var timestamp   = System.currentTimeMillis()
  var sessionID   = 0

  val eventdata    = new ListBuffer[SimpleMessageSession]
  var dataIterator = generateData()

  def getNext(): SimpleMessageSession = {
    if (!dataIterator.hasNext) {
      sessionID = sessionID + 1
      dataIterator = generateData()
    }
    dataIterator.next()
  }

  private def generateData(): Iterator[SimpleMessageSession] = {
    val windowLength = 5
    val session      = s"session_$sessionID"
    eventdata.clear()
    (0 until windowLength).foreach { _ ⇒
      eventdata += SimpleMessageSession(timestamp, value, session)
      timestamp = timestamp + 100
      value = value + 1
    }
    eventdata.iterator
  }
}
