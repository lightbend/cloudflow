package sensordata

import akka.NotUsed
import akka.stream.scaladsl.Source
import cloudflow.akkastream.WritableSinkRef

import sensordata.grpc.{ SensorData, SensorDataService, SensorReply }

import scala.concurrent.{ ExecutionContext, Future }

class SensorDataServiceImpl(sink: WritableSinkRef[SensorData])(implicit ec: ExecutionContext) extends SensorDataService {
  override def provide(in: SensorData): Future[SensorReply] = {
    println("howdy")
    sink.write(in).map(_ => { println("mapped"); SensorReply(s"Received ${in.payload}") })
  }
}
