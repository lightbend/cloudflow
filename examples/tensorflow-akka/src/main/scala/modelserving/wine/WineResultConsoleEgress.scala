package modelserving.wine

import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroInlet

import modelserving.wine.avro._

/**
 * Simply prints the Wine Results.
 */
final case object WineResultConsoleEgress extends AkkaStreamlet {

  val in = AvroInlet[WineResult]("in")

  final override val shape = StreamletShape.withInlets(in)

  override def createLogic = new RunnableGraphStreamletLogic() {

    def write(record: WineResult): Unit = println(record.toString)

    def runnableGraph = sourceWithOffsetContext(in).map(write(_)).to(sinkWithOffsetContext)
  }
}
