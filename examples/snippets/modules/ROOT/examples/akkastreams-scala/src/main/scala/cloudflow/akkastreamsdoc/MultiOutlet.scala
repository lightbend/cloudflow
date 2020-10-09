package cloudflow.akkastreamsdoc

// tag::sink2[]
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastream.util.scaladsl._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._

import scala.collection.immutable

class MultiOutlet extends AkkaStreamlet {
  val in      = AvroInlet[Data]("in")
  val invalid = AvroOutlet[DataInvalid]("invalid").withPartitioner(data ⇒ data.key)
  val valid   = AvroOutlet[Data]("valid").withPartitioner(RoundRobinPartitioner)
  val shape   = StreamletShape(in).withOutlets(invalid, valid)

  override def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph =
      sourceWithCommittableContext(in)
        .map { data =>
          if (data.value < 0) (immutable.Seq(DataInvalid(data.key, data.value, "All data must be positive numbers!")), immutable.Seq.empty)
          else (immutable.Seq.empty, immutable.Seq(data))
        }
        .to(MultiOutlet.sink(invalid, valid))
  }
}
// end::sink2[]
