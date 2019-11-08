package sensordata

import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import SensorDataJsonSupport._
import cloudflow.akkastream.AkkaServerStreamlet
import cloudflow.akkastream.util.scaladsl._
import cloudflow.streamlets.{ RoundRobinPartitioner, StreamletShape }
import cloudflow.streamlets.avro._

class SensorDataStreamingIngress extends AkkaServerStreamlet {
  val out = AvroOutlet[SensorData]("out", RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)

  implicit val entityStreamingSupport = EntityStreamingSupport.json()
  override def createLogic = HttpServerLogic.defaultStreaming(this, out)
}
