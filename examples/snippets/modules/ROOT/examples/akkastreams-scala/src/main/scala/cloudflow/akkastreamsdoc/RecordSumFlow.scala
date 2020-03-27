package cloudflow.akkastreamsdoc

//tag::all[]
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._

object RecordSumFlow extends AkkaStreamlet {

  //tag::definition[]
  val recordsInWindowParameter = IntegerConfigParameter(
    "records-in-window",
    "This value describes how many records of data should be processed together, default 64 records",
    Some(64)
  )
  //end::definition[]

  override def configParameters = Vector(recordsInWindowParameter)

  val inlet  = AvroInlet[Metric]("metric")
  val outlet = AvroOutlet[SummedMetric]("summed-metric")
  val shape  = StreamletShape.withInlets(inlet).withOutlets(outlet)

  def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph() = {
      //tag::usage[]
      val recordsInWindow = streamletConfig.getInt(recordsInWindowParameter.key)
      //end::usage[]
      val flow = FlowWithCommittableContext[Metric].grouped(recordsInWindow).map(sumRecords).mapContext(_.last)

      sourceWithOffsetContext(inlet)
        .via(flow)
        .to(committableSink(outlet))
    }
  }

  private def sumRecords(records: Seq[Metric]): SummedMetric =
    // ...
    //end::all[]
    ???
  //tag::all[]
}
//end::all[]
