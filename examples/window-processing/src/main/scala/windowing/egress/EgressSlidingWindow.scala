package windowing.egress

import akka.kafka.{ CommitWhen, CommitterSettings }
import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto.ProtoInlet
import cloudflow.akkastream.util.scaladsl.windowing.SlidingWindow

import window.datamodel.SimpleMessage

import scala.concurrent.duration._

final case object EgressSlidingWindow extends AkkaStreamlet {
  val in                   = ProtoInlet[SimpleMessage]("in")
  final override val shape = StreamletShape.withInlets(in)

  override final def createLogic = new RunnableGraphStreamletLogic() {

    val committerSettings = CommitterSettings(system).withCommitWhen(CommitWhen.OffsetFirstObserved)

    def runnableGraph() =
      sourceWithCommittableContext(in)
        .via(SlidingWindow[SimpleMessage](duration = 3.second, slide = 1.5.second, time_extractor = (msg) ⇒ msg.ts, watermark = 1.2.second))
        .map { records ⇒
          println("Got new Sliding window")
          records.foreach(record ⇒ println(s"      time ${record.ts} - value ${record.value}"))
        }
        .to(committableSink(committerSettings))
  }
}
