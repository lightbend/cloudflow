package swissknife

import cloudflow.flink.FlinkStreamlet
import org.apache.flink.streaming.api.scala._
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import cloudflow.flink._

class FlinkCounter extends FlinkStreamlet {

  @transient val in  = AvroInlet[Data]("in")
  @transient val out = AvroOutlet[Data]("out", _.src)

  @transient val shape = StreamletShape.withInlets(in).withOutlets(out)

  override def createLogic() = new FlinkStreamletLogic {
    override def buildExecutionGraph = {
      val stream: DataStream[Data] =
        readStream(in)
          .map { data ⇒
            data.copy(src = data.src + "-flink")
          }
      writeStream(out, stream)
    }
  }

}
