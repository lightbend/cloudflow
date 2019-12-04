package pipelines.examples.frauddetection.process

import java.io.File

import akka.Done
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Sink}
import akka.util.Timeout
import cloudflow.akkastream.AkkaStreamlet
import model.{Model, MultiModelFactory}
import model.actor.ModelServingActor
import modelserving.model.{ModelDescriptor, ModelType}
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.{AvroInlet, AvroOutlet}
import pipelines.examples.frauddetection.data.{CustomerTransaction, ScoreFromTheModel, ScoredTransaction}
import pipelines.examples.frauddetection.models.tensorflow.{FraudTensorFlowBundledModelFactory, FraudTensorFlowModelFactory}

import scala.concurrent.duration._

final case object ScoreTransactionsAndServeModels extends AkkaStreamlet {

  //\\//\\//\\ INLETS //\\//\\//\\
  val transactionsComeInHere = AvroInlet[CustomerTransaction]("transactions")

  //\\//\\//\\ OUTLETS //\\//\\//\\
  val scoredTransactionsComeOutHere = AvroOutlet[ScoredTransaction]("results")

  //\\//\\//\\ SHAPE //\\//\\//\\
  final override val shape = StreamletShape.withInlets(transactionsComeInHere).withOutlets(scoredTransactionsComeOutHere)

  val modelFactory = MultiModelFactory(
    Map(
      ModelType.TENSORFLOW -> FraudTensorFlowModelFactory,
      ModelType.TENSORFLOWSAVED -> FraudTensorFlowBundledModelFactory,
    ))

  //\\//\\//\\ LOGIC //\\//\\//\\
  final override def createLogic = new RunnableGraphStreamletLogic() {


    def runnableGraph() = {
      plainSource(transactionsComeInHere).via(theTransactionsFlow).to(plainSink(scoredTransactionsComeOutHere))
    }

    implicit val askTimeout: Timeout = Timeout(30.seconds)

    val modelserver = context.system.actorOf(
      ModelServingActor.props[CustomerTransaction, Double](
        "fraud",
        modelFactory,
        () ⇒ 0.0),
        )

    protected def theTransactionsFlow =
      Flow[CustomerTransaction].mapAsync(1) { transaction ⇒
        modelserver.ask(transaction).mapTo[Model.ModelReturn[Double]]
          .map { modelReturn ⇒
            val result = ScoreFromTheModel(value = modelReturn.modelOutput)
            log.info("ML Result: "+result)

            ScoredTransaction(transaction, result)
          }
      }

    protected def theModelFlow =
      Flow[ModelDescriptor].mapAsync(1) {
        descriptor ⇒ modelserver.ask(descriptor).mapTo[Done]
      }
  }
}
