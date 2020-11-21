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

final case object DelayedDataIngress extends AkkaStreamlet {

  private val frequency: FiniteDuration = 100.millisecond // Message emit frequency
  private val submitter                 = WindowDataSubmitter()

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

case class WindowDataSubmitter(duration: Long = 3000, step: Long = 500, value: Long = 0, head: Int = 2, tail: Int = 2) {

  val steps = (duration / step).toInt
  require(steps > (head + tail))
  var currentWindow            = WindowToSubmit(System.currentTimeMillis(), duration, step, value, None, this, head, tail)
  def getNext(): SimpleMessage = currentWindow.getNext()
}

case class WindowToSubmit(startTime: Long,
                          duration: Long,
                          step: Long,
                          value: Long,
                          previous: Option[WindowToSubmit],
                          parent: WindowDataSubmitter,
                          head: Int,
                          tail: Int) {

  object State extends Enumeration {
    type State = Value
    val HEAD, PREVIOUS, BODY, TAIL = Value
  }

  val nsteps = (duration / step).toInt

  // Build iterators for state
  var currentTime  = startTime
  var currentValue = value
  val headList     = new ListBuffer[SimpleMessage]
  (0 until head).foreach { _ ⇒
    headList += SimpleMessage(currentTime, currentValue)
    currentTime = currentTime + step
    currentValue = currentValue + 1
  }

  val headIterator = headList.iterator

  val bodyStept = nsteps - (head + tail)
  val bodyList  = new ListBuffer[SimpleMessage]
  (0 until bodyStept).foreach { _ ⇒
    bodyList += SimpleMessage(currentTime, currentValue)
    currentTime = currentTime + step
    currentValue = currentValue + 1
  }

  val bodyIterator = bodyList.iterator

  val tailList = new ListBuffer[SimpleMessage]
  (0 until tail).foreach { _ ⇒
    tailList += SimpleMessage(currentTime, currentValue)
    currentTime = currentTime + step
    currentValue = currentValue + 1
  }

  val tailIterator = tailList.iterator

  var state = State.HEAD

  private def hasMoredata(): Boolean =
    state match {
      case State.HEAD ⇒ headIterator.hasNext
      case State.BODY ⇒ bodyIterator.hasNext
      case State.TAIL ⇒ tailIterator.hasNext
      case _          ⇒ true
    }

  private def mayBeNext(): Option[SimpleMessage] =
    state match {
      case State.HEAD ⇒ if (headIterator.hasNext) Some(headIterator.next()) else None
      case State.BODY ⇒ if (bodyIterator.hasNext) Some(bodyIterator.next()) else None
      case State.TAIL ⇒ if (tailIterator.hasNext) Some(tailIterator.next()) else None
      case _          ⇒ if (previous.get.hasMoredata()) Some(previous.get.getNext()) else None
    }

  def getNext(): SimpleMessage =
    mayBeNext() match {
      case Some(value) ⇒ value
      case _           ⇒
        // switch state and get data
        state match {
          case State.HEAD ⇒
            if (previous.isEmpty) state = State.BODY else state = State.PREVIOUS
            mayBeNext().get
          case State.PREVIOUS ⇒
            state = State.BODY
            mayBeNext().get
          case State.BODY ⇒
            state = State.TAIL
            // Start new Window
            val nextWindow = WindowToSubmit(currentTime, duration, step, currentValue, Some(this), parent, head, tail)
            parent.currentWindow = nextWindow
            parent.getNext()
        }
    }
}
