package sensordata

//tag::logic[]
import akka.grpc.scaladsl.ServerReflection
//end::logic[]
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.RouteDirectives

import cloudflow.akkastream._
//tag::logic[]
import cloudflow.akkastream.util.scaladsl.GrpcServerLogic
//end::logic[]
import cloudflow.streamlets._
import cloudflow.streamlets.proto.ProtoOutlet

//tag::logic[]
import sensordata.grpc.{ SensorData, SensorDataService, SensorDataServiceHandler }

//end::logic[]

//tag::logic[]
class SensorDataIngress extends AkkaServerStreamlet {
  // ...

  //end::logic[]

  val out   = ProtoOutlet[SensorData]("out", RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)

//tag::logic[]
  override def createLogic = new GrpcServerLogic(this) {
    override def handlers() =
      List(SensorDataServiceHandler.partial(new SensorDataServiceImpl(sinkRef(out))), ServerReflection.partial(List(SensorDataService)))
  }
//end::logic[]
}
