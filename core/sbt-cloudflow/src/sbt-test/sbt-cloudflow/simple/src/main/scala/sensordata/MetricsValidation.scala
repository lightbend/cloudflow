package sensordata

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastream.util.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class MetricsValidation extends AkkaStreamlet {
  val in      = AvroInlet[Metric]("in")
  val invalid = AvroOutlet[InvalidMetric]("invalid").withPartitioner(metric ⇒ metric.metric.deviceId.toString)
  val valid   = AvroOutlet[Metric]("valid").withPartitioner(RoundRobinPartitioner)
  val shape   = StreamletShape(in).withOutlets(invalid, valid)

  override def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = sourceWithCommittableContext(in).to(Splitter.sink(flow, invalid, valid))
    def flow =
      FlowWithCommittableContext[Metric]
        .map { metric ⇒
          if (!SensorDataUtils.isValidMetric(metric)) Left(InvalidMetric(metric, "All measurements must be positive numbers!"))
          else Right(metric)
        }
  }
}
