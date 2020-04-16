package cloudflow.akkastreamsdoc

// tag::httpIngress[]
import cloudflow.akkastream._
import cloudflow.akkastream.util.scaladsl._

import cloudflow.streamlets.avro._
import cloudflow.streamlets._

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import JsonSupport._

class DataHttpIngress extends AkkaServerStreamlet {
  val out         = AvroOutlet[Data]("out").withPartitioner(RoundRobinPartitioner)
  def shape       = StreamletShape.withOutlets(out)
  def createLogic = HttpWriterLogic.default(this, out)
}
// end::httpIngress[]
