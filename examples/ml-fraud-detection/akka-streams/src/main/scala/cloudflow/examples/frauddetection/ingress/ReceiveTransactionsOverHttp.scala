package cloudflow.examples.frauddetection.ingress

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import cloudflow.akkastream.AkkaServerStreamlet
import cloudflow.akkastream.util.scaladsl.HttpServerLogic
import cloudflow.examples.frauddetection.data.CustomerTransaction
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroOutlet
import cloudflow.examples.frauddetection.utils.CustomerTransactionProtocol._

class ReceiveTransactionsOverHttp extends AkkaServerStreamlet {

  //\\//\\//\\ INLETS //\\//\\//\\

  //\\//\\//\\ OUTLETS //\\//\\//\\
  val out = AvroOutlet[CustomerTransaction]("transactions")

  //\\//\\//\\ SHAPE //\\//\\//\\
  final override val shape = StreamletShape.withOutlets(out)

  //\\//\\//\\ LOGIC //\\//\\//\\
  final override def createLogic =
    HttpServerLogic.default(this, out)
}

