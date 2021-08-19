package cloudflow.akkastreamsdoc

// tag::merge[]
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastream.util.scaladsl._
import cloudflow.streamlets.avro._
import cloudflow.streamlets._

class DataMerge extends AkkaStreamlet {

  val in0 = AvroInlet[Data]("in-0")
  val in1 = AvroInlet[Data]("in-1")
  val out = AvroOutlet[Data]("out", d => d.key)

  final override val shape = StreamletShape.withInlets(in0, in1).withOutlets(out)

  override final def createLogic = new RunnableGraphStreamletLogic {
    def runnableGraph =
      Merger.source(in0, in1).to(committableSink(out))
  }
}
// end::merge[]
