package cloudflow.akkastreamsdoc

import cloudflow.akkastream._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream.util.scaladsl.SplitterLogic

class DataSplitter extends AkkaStreamlet {
  val in      = AvroInlet[Data]("in")
  val invalid = AvroOutlet[DataInvalid]("invalid").withPartitioner(data ⇒ data.key)
  val valid   = AvroOutlet[Data]("valid").withPartitioner(RoundRobinPartitioner)
  val shape   = StreamletShape(in).withOutlets(invalid, valid)

  override def createLogic = new SplitterLogic(in, invalid, valid) {
    def flow = flowWithOffsetContext()
        .map { data ⇒
          if (data.value < 0) Left(DataInvalid(data.key, data.value, "All measurements must be positive numbers!"))
          else Right(data)
        }
  }
}