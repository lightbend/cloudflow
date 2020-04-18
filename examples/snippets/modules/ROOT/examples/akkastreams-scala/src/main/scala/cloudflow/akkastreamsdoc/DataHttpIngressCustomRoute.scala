package cloudflow.akkastreamsdoc

import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model._

import cloudflow.akkastream._
import cloudflow.akkastream.util.scaladsl._

import cloudflow.streamlets.avro._
import cloudflow.streamlets._

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import JsonSupport._

// tag::customRoute[]
class DataHttpIngressCustomRoute extends AkkaServerStreamlet {
  val out   = AvroOutlet[Data]("out").withPartitioner(RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)
  def createLogic = new HttpServerLogic(this) {
    val writer = sinkRef(outlet)
    override def route(): Route =
      put {
        entity(as[Data]) { data ⇒
          onSuccess(writer.write(data)) { _ ⇒
            complete(StatusCodes.OK)
          }
        }
      }
  }
}
// end::customRoute[]
