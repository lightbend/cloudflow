package com.example

//tag::processor[]
import org.apache.flink.streaming.api.scala._
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro._
import cloudflow.flink._

class FlinkProcessor extends FlinkStreamlet {

  // Step 1: Define inlets and outlets. Note for the outlet you need to specify
  //         the partitioner function explicitly
  val in  = AvroInlet[Data]("in")
  val out = AvroOutlet[Data]("out", _.id.toString)

  // Step 2: Define the shape of the streamlet. In this example the streamlet
  //         has 1 inlet and 1 outlet
  val shape = StreamletShape(in, out)

  // Step 3: Provide custom implementation of `FlinkStreamletLogic` that defines
  //         the behavior of the streamlet
  override def createLogic() = new FlinkStreamletLogic {
    override def buildExecutionGraph = {
      val ins: DataStream[Data]  = readStream(in)
      val outs: DataStream[Data] = ins.filter(_.id % 2 == 0)
      writeStream(out, outs)
    }
  }
}
//end::processor[]
