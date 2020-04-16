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

object HttpWriterLogic {
  final def default[Out](
      server: Server,
      outlet: CodecOutlet[Out]
  )(implicit
    context: AkkaStreamletContext,
    fbu: FromByteStringUnmarshaller[Out]) =
    new HttpWriterLogic(server, outlet) {
      final override def route(): Route = defaultRoute(writer)
    }

  final def defaultStreaming[Out](
      server: Server,
      outlet: CodecOutlet[Out]
  )(
      implicit
      context: AkkaStreamletContext,
      fbs: FromByteStringUnmarshaller[Out],
      ess: EntityStreamingSupport
  ) =
    new StreamingHttpWriterLogic(server, outlet) {
      def entityStreamingSupport: EntityStreamingSupport = ess
      final override def route(): Route                  = defaultStreamingRoute(writer)
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
 * Accepts and transcodes HTTP requests, then writes the transcoded data to the outlet.
 * By default this `HttpWriterLogic` accepts PUT or POST requests containing entities that can be unmarshalled using the FromByteStringUnmarshaller.
 * The HttpWriterLogic requires a `Server` to be passed in when it is created. You need to pass in a Server to create it
 * [[AkkaServerStreamlet]] extends [[Server]], which can be used for this purpose.
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
 *    override def createLogic = new HttpWriterLogic(this, outlet) {
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
abstract class HttpWriterLogic[Out](
    server: Server,
    outlet: CodecOutlet[Out]
)(implicit context: AkkaStreamletContext, fbu: FromByteStringUnmarshaller[Out])
    extends HttpServerLogic(server) {
  implicit def fromEntityUnmarshaller: FromEntityUnmarshaller[Out] =
    PredefinedFromEntityUnmarshallers.byteStringUnmarshaller
      .andThen(implicitly[FromByteStringUnmarshaller[Out]])

  /**
   * a WritableSinkRef[Out], which can be used to write to the outlet.
   */
  protected val writer = sinkRef(outlet)

  /**
   * Java API
   */
  final protected def getWriter() = writer
  override def flow               = Route.handlerFlow(route())
}

/**
 * Accepts and transcodes HTTP requests.
 * The HttpServerLogic requires a `Server` to be passed in when it is created. You need to pass in a Server to create it
 * [[AkkaServerStreamlet]] extends [[Server]], which can be used for this purpose.
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
   * The method to override to supply a custom processing logic
   *
   * @return the HTTP route
   */
  def route(): Route

  protected def flow = Route.handlerFlow(route())

  def run() =
    startServer(
      context,
      flow,
      containerPort
    )

  protected def startServer(
      context: AkkaStreamletContext,
      handler: Flow[HttpRequest, HttpResponse, _],
      port: Int
  ): Unit =
    Http()
      .bindAndHandle(handler, "0.0.0.0", port)
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

/**
 * Accepts and transcodes HTTP requests that contains framed elements, then writes the transcoded data to the outlet.
 * You need to implement the `entityStreamingSupport` method, which must return an `EntityStreamingSupport` which
 * allows rendering and receiving incoming ``Source[T, _]`` from HTTP entities.
 * The elements in the source are unmarshalled using the `FromByteStringUnmarshaller`.
 *
 * This [[HttpWriterLogic]] requires a [[Server]] to be passed in when it is created.
 * [[AkkaServerStreamlet]] extends [[Server]], which can be used for this purpose.
 * When you define the [[StreamingHttpWriterLogic]] inside the streamlet, you can just pass in `this`:
 * {{{
 *  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
 *
 *  object TestHttpServer extends AkkaServerStreamlet {
 *    implicit val jsonformatData: RootJsonFormat[Data] = jsonFormat2(Data.apply)
 *    val outlet = AvroOutlet[Data]("out", _.id.toString)
 *    val shape = StreamletShape(outlet)
 *
 *    override def createLogic = new StreamingHttpWriterLogic(this, outlet) {
 *      def entityStreamingSupport = EntityStreamingSupport.json()
 *    }
 *  }
 * }}}
 */
abstract class StreamingHttpWriterLogic[Out: FromByteStringUnmarshaller](
    server: Server,
    outlet: CodecOutlet[Out]
)(implicit context: AkkaStreamletContext)
    extends HttpWriterLogic(server, outlet) {
  implicit def entityStreamingSupport: EntityStreamingSupport

  /**
   * The method to override to supply a custom processing logic
   *
   * @param writer the writer to write to
   * @return the HTTP route
   */
  override def route(): Route =
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
