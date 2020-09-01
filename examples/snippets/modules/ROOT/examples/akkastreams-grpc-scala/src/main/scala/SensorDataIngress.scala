package sensordata

import akka.grpc.scaladsl.{ ServerReflection, ServiceHandler }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.RouteDirectives

import cloudflow.akkastream._
import cloudflow.akkastream.util.scaladsl.GrpcServerLogic
import cloudflow.streamlets._
import cloudflow.streamlets.proto.ProtoOutlet

import sensordata.grpc.{ SensorData, SensorDataService, SensorDataServiceHandler }

class SensorDataIngress extends AkkaServerStreamlet {
  val out   = ProtoOutlet[SensorData]("out", RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)

//tag::logic[]
  override def createLogic = new GrpcServerLogic(this) {
    override def handlers() =
      List(SensorDataServiceHandler.partial(new SensorDataServiceImpl(sinkRef(out))), ServerReflection.partial(List(SensorDataService)))
  }
//end::logic[]
}
