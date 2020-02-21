package cloudflow.akkastreamsdoc

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.common.EntityStreamingSupport

import cloudflow.akkastream._
import cloudflow.akkastream.util.scaladsl._

import cloudflow.streamlets.avro._
import cloudflow.streamlets._

import JsonSupport._

class DataStreamingIngress extends AkkaServerStreamlet {
  val out   = AvroOutlet[Data]("out", RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)

  implicit val entityStreamingSupport = EntityStreamingSupport.json()
  override def createLogic            = HttpServerLogic.defaultStreaming(this, out)
}