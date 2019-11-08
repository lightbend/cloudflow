package sensordata

import cloudflow.akkastream._
import cloudflow.akkastream.util.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class MetricsValidation extends AkkaStreamlet {
  val in = AvroInlet[Metric]("in")
  val invalid = AvroOutlet[InvalidMetric]("invalid").withPartitioner(metric ⇒ metric.metric.deviceId.toString)
  val valid = AvroOutlet[Metric]("valid").withPartitioner(RoundRobinPartitioner)
  val shape = StreamletShape(in).withOutlets(invalid, valid)

  override def createLogic = new SplitterLogic(in, invalid, valid) {
    def flow = flowWithOffsetContext()
      .map { metric ⇒
        if (!SensorDataUtils.isValidMetric(metric)) Left(InvalidMetric(metric, "All measurements must be positive numbers!"))
        else Right(metric)
      }
  }
}
