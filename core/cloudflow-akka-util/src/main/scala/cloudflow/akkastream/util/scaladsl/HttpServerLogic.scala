/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.akkastream.util.scaladsl

import scala.concurrent._
import scala.util._

import akka.http.scaladsl._
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._

import akka.stream.Materializer
import akka.stream.scaladsl._

import cloudflow.streamlets._
import cloudflow.akkastream._

/**
 * Creates [[HttpServerLogic]]s that can be used to write data to an outlet that has been received by PUT or POST requests.
 */
object HttpServerLogic {
  final def default[Out](
      server: Server,
      outlet: CodecOutlet[Out]
  )(implicit
    context: AkkaStreamletContext,
    fbu: FromByteStringUnmarshaller[Out]): HttpServerLogic = {
    implicit def fromEntityUnmarshaller: FromEntityUnmarshaller[Out] =
      PredefinedFromEntityUnmarshallers.byteStringUnmarshaller
        .andThen(implicitly[FromByteStringUnmarshaller[Out]])

    new HttpServerLogic(server) {
      final override def route(): Route = defaultRoute(sinkRef(outlet))
    }
  }

  final def defaultStreaming[Out](
      server: Server,
      outlet: CodecOutlet[Out]
  )(
      implicit
      context: AkkaStreamletContext,
      fbs: FromByteStringUnmarshaller[Out],
      ess: EntityStreamingSupport
  ): HttpServerLogic =
    new HttpServerLogic(server) {
      final override def route(): Route = defaultStreamingRoute(sinkRef(outlet))
    }

  final def defaultRoute[Out](writer: WritableSinkRef[Out])(implicit fru: FromRequestUnmarshaller[Out]) =
    logRequest("defaultRoute") {
      logResult("defaultRoute") {
        (put | post) {
          entity(as[Out]) { out ⇒
            onSuccess(writer.write(out)) { _ ⇒
              complete(StatusCodes.Accepted)
            }
          }
        }
      }
    }

  final def defaultStreamingRoute[Out](
      writer: WritableSinkRef[Out]
  )(implicit mat: Materializer, ec: ExecutionContext, fbs: FromByteStringUnmarshaller[Out], ess: EntityStreamingSupport): Route =
    entity(asSourceOf[Out]) { elements ⇒
      val written: Future[_] =
        elements
          .mapAsync(1)(out ⇒ writer.write(out))
          .toMat(Sink.ignore)(Keep.right)
          .run

      complete {
        written.map { _ ⇒
          StatusCodes.Accepted
        }
      }
    }
}

/**
 * [[cloudflow.akkastream.ServerStreamletLogic]] for accepting HTTP requests.
 * The HttpServerLogic requires a `Server` to be passed in when it is created. You need to pass in a Server to create it
 * [[cloudflow.akkastream.AkkaServerStreamlet]] extends [[cloudflow.akkastream.Server]], which can be used for this purpose.
 * When you define the logic inside the streamlet, you can just pass in `this`:
 * {{{
 *  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
 *
 *  object TestHttpServer extends AkkaServerStreamlet {
 *    implicit val jsonformatData: RootJsonFormat[Data] = jsonFormat2(Data.apply)
 *
 *    val outlet = AvroOutlet[Data]("out", _.id.toString)
 *    val shape = StreamletShape(outlet)
 *
 *    override def createLogic = new HttpServerLogic(this) {
 *      val writer = sinkRef(outlet)
 *      override def route(): Route = {
 *        put {
 *          entity(as[Data]) { data ⇒
 *            if (data.id == 42) {
 *              onSuccess(writer.write(data)) { _ ⇒
 *                complete(StatusCodes.OK)
 *              }
 *            } else complete(StatusCodes.BadRequest)
 *          }
 *        }
 *      }
 *    }
 *  }
 * }}}
 */
abstract class HttpServerLogic(
    server: Server
)(implicit context: AkkaStreamletContext)
    extends ServerStreamletLogic(server) {

  /**
   * Override this method to define the HTTP route that this HttpServerLogic will use.
   * @return the Route that will be used to handle HTTP requests.
   */
  def route(): Route

  def run() =
    startServer(
      context,
      route(),
      containerPort
    )

  protected def startServer(
      context: AkkaStreamletContext,
      route: Route,
      port: Int
  ): Unit =
    Http()
      .newServerAt("0.0.0.0", port)
      .bind(route)
      .map { binding ⇒
        context.signalReady()
        system.log.info(s"Bound to ${binding.localAddress.getHostName}:${binding.localAddress.getPort}")
        // this only completes when StreamletRef executes cleanup.
        context.onStop { () ⇒
          system.log.info(s"Unbinding from ${binding.localAddress.getHostName}:${binding.localAddress.getPort}")
          binding.unbind().map(_ ⇒ Dun)
        }
        binding
      }
      .andThen {
        case Failure(cause) ⇒
          system.log.error(cause, s"Failed to bind to $port.")
          context.stop()
      }
}
