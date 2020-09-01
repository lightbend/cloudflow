package sensordata

import akka.stream.scaladsl.RunnableGraph
import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto.ProtoInlet
import sensordata.grpc.SensorData

class Logger extends AkkaStreamlet {
  val inlet = ProtoInlet[SensorData]("in")
  val shape = StreamletShape.withInlets(inlet)

  override def createLogic = new RunnableGraphStreamletLogic() {
    override def runnableGraph(): RunnableGraph[_] =
      sourceWithCommittableContext(inlet)
        .map { data =>
          println(s"Saw ${data.payload}")
        }
        .to(committableSink)
  }
}
