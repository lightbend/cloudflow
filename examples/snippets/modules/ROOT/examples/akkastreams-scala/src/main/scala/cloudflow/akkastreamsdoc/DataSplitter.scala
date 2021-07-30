package cloudflow.akkastreamsdoc

// tag::splitter[]
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastream.util.scaladsl._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class DataSplitter extends AkkaStreamlet {
  val in      = AvroInlet[Data]("in")
  val invalid = AvroOutlet[DataInvalid]("invalid").withPartitioner(data => data.key)
  val valid   = AvroOutlet[Data]("valid").withPartitioner(RoundRobinPartitioner)
  val shape   = StreamletShape(in).withOutlets(invalid, valid)

  override def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = sourceWithCommittableContext(in).to(Splitter.sink(flow, invalid, valid))
    def flow =
      FlowWithCommittableContext[Data]
        .map { data =>
          if (data.value < 0) Left(DataInvalid(data.key, data.value, "All data must be positive numbers!"))
          else Right(data)
        }
  }
}
// end::splitter[]
