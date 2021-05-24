package processor

import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.functions.co._
import org.apache.flink.api.common.state.{ ValueState, ValueStateDescriptor }
import org.apache.flink.util.Collector

import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import datamodel._
import cloudflow.flink._

class Processor extends FlinkStreamlet {

  @transient val in = AvroInlet[Data]("in")
  @transient val out = AvroOutlet[Data]("out", _.id.toString)

  @transient val shape = StreamletShape.withInlets(in).withOutlets(out)

  override def createLogic() = new FlinkStreamletLogic {
    override def buildExecutionGraph = {
      val processed: DataStream[Data] =
        readStream(in)
          .map { data =>
            Data(data.id, data.msg + "-flink")
          }

      writeStream(out, processed)
    }
  }
}
