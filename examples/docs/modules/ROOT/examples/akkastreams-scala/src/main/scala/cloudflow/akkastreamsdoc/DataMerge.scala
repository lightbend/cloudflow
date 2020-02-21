package cloudflow.akkastreamsdoc

import cloudflow.akkastream._
import cloudflow.streamlets.avro._
import cloudflow.streamlets._

import cloudflow.akkastream.util.scaladsl.MergeLogic

class DataMerge extends AkkaStreamlet {

  val in0 = AvroInlet[Data]("in-0")
  val in1 = AvroInlet[Data]("in-1")
  val out = AvroOutlet[Data]("out", d ⇒ d.key)

  final override val shape = StreamletShape.withInlets(in0, in1).withOutlets(out)

  override final def createLogic = new MergeLogic(Vector(in0, in1), out)
}