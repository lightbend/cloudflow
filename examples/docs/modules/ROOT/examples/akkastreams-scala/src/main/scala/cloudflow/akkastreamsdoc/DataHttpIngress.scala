package cloudflow.akkastreamsdoc

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import cloudflow.akkastream._
import cloudflow.akkastream.util.scaladsl._

import cloudflow.streamlets.avro._
import cloudflow.streamlets._

import JsonSupport._

class DataHttpIngress extends AkkaServerStreamlet {
  val out                  = AvroOutlet[Data]("out").withPartitioner(RoundRobinPartitioner)
  def shape                = StreamletShape.withOutlets(out)
  override def createLogic = HttpServerLogic.default(this, out)
}