package cloudflow.examples.frauddetection.process

import cloudflow.akkastream.util.scaladsl.MergeLogic
import cloudflow.akkastream.{ AkkaStreamlet, StreamletLogic }
import cloudflow.examples.frauddetection.data.CustomerTransaction
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.{ AvroInlet, AvroOutlet }

class MergeTransactions extends AkkaStreamlet {

  //\\//\\//\\ INLETS //\\//\\//\\
  val fromTheLeft = AvroInlet[CustomerTransaction]("left")
  val fromTheRight = AvroInlet[CustomerTransaction]("right")

  //\\//\\//\\ OUTLETS //\\//\\//\\
  val everythingGoesOutHere = AvroOutlet[CustomerTransaction]("transactions")

  //\\//\\//\\ SHAPE //\\//\\//\\
  final override val shape = StreamletShape.withInlets(fromTheLeft, fromTheRight).withOutlets(everythingGoesOutHere)

  //\\//\\//\\ LOGIC //\\//\\//\\
  final override def createLogic(): StreamletLogic =
    new MergeLogic[CustomerTransaction](Vector(fromTheLeft, fromTheRight), everythingGoesOutHere)
}
