package cloudflow.examples.frauddetection.egress

import akka.NotUsed
import akka.stream.ClosedShape
import akka.stream.scaladsl.{ Flow, GraphDSL, Merge, RunnableGraph, Sink }
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.akkastream.{ AkkaStreamlet, StreamletLogic }
import cloudflow.examples.frauddetection.data.{ CustomerTransaction, ScoredTransaction }
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroInlet

/**
 * Logs all Transactions at the end of our stream
 */
class LogCustomerTransactions extends AkkaStreamlet {

  //\\//\\//\\ INLETS //\\//\\//\\
  val fromTheModel = AvroInlet[ScoredTransaction]("model")
  val fromTheMerchant = AvroInlet[CustomerTransaction]("merchant")

  //\\//\\//\\ OUTLETS //\\//\\//\\

  //\\//\\//\\ SHAPE //\\//\\//\\
  final override val shape = StreamletShape.withInlets(fromTheModel, fromTheMerchant)

  //\\//\\//\\ LOGIC //\\//\\//\\
  final override def createLogic(): StreamletLogic = new RunnableGraphStreamletLogic() {

    val theModelFlow = Flow[ScoredTransaction].map { stx ⇒
      if (stx.modelResult.value > 0.7) {
        log.info(s"${stx.inputRecord.transactionId} ********************* FRAUD *********************")
      } else {
        log.info(s"${stx.inputRecord.transactionId} Ok")
      }

      stx.inputRecord
    }

    val theMerchantFlow = Flow[CustomerTransaction].map { tx ⇒
      log.info(s"${tx.transactionId} Ok")
      tx
    }

    def runnableGraph() =
      RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] ⇒
        import GraphDSL.Implicits._

        val model = plainSource(fromTheModel).via(theModelFlow)
        val merchant = plainSource(fromTheMerchant).via(theMerchantFlow)
        val out = Sink.ignore

        val merge = builder.add(Merge[(CustomerTransaction)](2))

        model ~> merge ~> out
        merchant ~> merge

        ClosedShape
      })
  }
}
