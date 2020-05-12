package cloudflow.installer

import akka.actor._
import akka.event.LoggingAdapter
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream._

import scala.concurrent._
import scala.util._

object HealthChecks {
  def serve(settings: Settings)(implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext, log: LoggingAdapter) =
    Http()
      .bindAndHandle(
        route,
        settings.api.bindInterface,
        settings.api.bindPort
      )
      .onComplete {
        case Success(serverBinding) ⇒
          log.info(s"Bound to ${serverBinding.localAddress}.")
        case Failure(e) ⇒
          log.error(e, s"Failed to bind.")
          system.terminate().foreach { _ ⇒
            println("Exiting, could not bind http.")
            sys.exit(-1)
          }
      }
  def route =
    // format: OFF
    path("robots.txt") {
      getFromResource("robots.txt")
    } ~
    pathPrefix("checks") {
      path("healthy") {
        complete(StatusCodes.OK)
      } ~
      path("ready") {
        complete(StatusCodes.OK)
      }
    }
  // format: ON
}
