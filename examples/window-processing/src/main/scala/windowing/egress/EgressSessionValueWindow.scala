package windowing.egress

import akka.kafka.{ CommitWhen, CommitterSettings }
import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto.ProtoInlet
import cloudflow.akkastream.util.scaladsl.windowing.SessionValueWindow

import window.datamodel.SimpleMessageSession

import scala.concurrent.duration._

final case object EgressSessionValueWindow extends AkkaStreamlet {
  val in                   = ProtoInlet[SimpleMessageSession]("in")
  final override val shape = StreamletShape.withInlets(in)

  override final def createLogic = new RunnableGraphStreamletLogic() {

    val committerSettings = CommitterSettings(system).withCommitWhen(CommitWhen.OffsetFirstObserved)

    def runnableGraph() =
      sourceWithCommittableContext(in)
      //      .map(r ⇒ { println(s"Got new record $r"); r })
        .via(SessionValueWindow[SimpleMessageSession](session_extractor = (msg) ⇒ msg.session, inactivity = 1.second))
        .map { records ⇒
          println("Got new session window")
          records.foreach(record ⇒ println(s"      session ${record.session} time ${record.ts} - value ${record.value}"))
        }
        .to(committableSink(committerSettings))
  }
}
