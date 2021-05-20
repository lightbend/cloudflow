package logger

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import datamodel._

class Logger extends AkkaStreamlet {
  val inlet = AvroInlet[Data]("in")
  val shape = StreamletShape.withInlets(inlet)

  override def createLogic = new RunnableGraphStreamletLogic() {
    def log(data: Data) =
      system.log.info(s"${data.id} : ${data.msg}-akka")

    def flow =
      FlowWithCommittableContext[Data]
        .map { data ⇒
          log(data)
          data
        }

    def runnableGraph =
      sourceWithCommittableContext(inlet)
        .via(flow)
        .to(committableSink)
  }
}
