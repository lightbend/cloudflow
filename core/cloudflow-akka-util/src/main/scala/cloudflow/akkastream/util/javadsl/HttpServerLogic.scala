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

package cloudflow.akkastream.util.javadsl

import akka.NotUsed
import akka.util.ByteString
import akka.http.javadsl.unmarshalling.Unmarshaller
import akka.http.javadsl.server.directives.RouteAdapter
import akka.http.javadsl.common.EntityStreamingSupport
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.server._

import cloudflow._
import cloudflow.akkastream._
import cloudflow.streamlets._

/**
 * Creates [[HttpServerLogic]]s that can be used to write data to an outlet that has been received by PUT or POST requests.
 */
object HttpServerLogic {

  /**
   * Creates a HttpServerLogic that receives POST or PUT requests, unmarshals using the `fromByteStringUnmarshaller` and
   * writes the data to the providec `outlet`.
   */
  final def createDefault[Out](
      server: Server,
      outlet: CodecOutlet[Out],
      fromByteStringUnmarshaller: Unmarshaller[ByteString, Out],
      context: AkkaStreamletContext
  ): HttpServerLogic =
    createDefault(server, outlet, None, fromByteStringUnmarshaller, context)

  /**
   * Creates a HttpServerLogic that receives POST or PUT requests, unmarshals using the `fromByteStringUnmarshaller` and
   * writes the data to the providec `outlet`.
   * A rejection handler can be provided to deal with rejections.
   */
  final def createDefault[Out](
      server: Server,
      outlet: CodecOutlet[Out],
      rejectionHandler: akka.http.javadsl.server.RejectionHandler,
      fromByteStringUnmarshaller: Unmarshaller[ByteString, Out],
      context: AkkaStreamletContext
  ): HttpServerLogic =
    createDefault(server, outlet, Some(rejectionHandler.asScala), fromByteStringUnmarshaller, context)

  private def createDefault[Out](
      server: Server,
      outlet: CodecOutlet[Out],
      rejectionHandler: Option[RejectionHandler],
      fromByteStringUnmarshaller: Unmarshaller[ByteString, Out],
      context: AkkaStreamletContext
  ): HttpServerLogic =
    new HttpServerLogic(server, context) {
      implicit def fromEntityUnmarshaller: FromEntityUnmarshaller[Out] =
        PredefinedFromEntityUnmarshallers.byteStringUnmarshaller
          .andThen(fromByteStringUnmarshaller.asScala)
      final override def createRoute(): akka.http.javadsl.server.Route =
        RouteAdapter.asJava(
          akkastream.util.scaladsl.HttpServerLogic
            .defaultRoute(rejectionHandler, sinkRef(outlet))
        )
    }

  /**
   * Creates a HttpServerLogic that receives streaming POST or PUT requests, unmarshals using the `fromByteStringUnmarshaller` and
   * the provided `EntityStreamingSupport` and writes the data to the providec `outlet`.
   */
  final def createDefaultStreaming[Out](
      server: Server,
      outlet: CodecOutlet[Out],
      fromByteStringUnmarshaller: Unmarshaller[ByteString, Out],
      ess: EntityStreamingSupport,
      context: AkkaStreamletContext
  ): HttpServerLogic = new HttpServerLogic(server, context) {
    implicit val fbu         = fromByteStringUnmarshaller.asScala
    implicit val essDelegate = EntityStreamingSupportDelegate(ess)
    final override def createRoute(): akka.http.javadsl.server.Route =
      RouteAdapter.asJava(akkastream.util.scaladsl.HttpServerLogic.defaultStreamingRoute(sinkRef(outlet)))
  }

  final case class EntityStreamingSupportDelegate(entityStreamingSupport: akka.http.javadsl.common.EntityStreamingSupport)
      extends akka.http.scaladsl.common.EntityStreamingSupport {
    def supported: akka.http.scaladsl.model.ContentTypeRange =
      entityStreamingSupport.supported.asInstanceOf[akka.http.scaladsl.model.ContentTypeRange]
    def contentType: akka.http.scaladsl.model.ContentType =
      entityStreamingSupport.contentType.asInstanceOf[akka.http.scaladsl.model.ContentType]
    def framingDecoder: akka.stream.scaladsl.Flow[ByteString, ByteString, NotUsed]  = entityStreamingSupport.getFramingDecoder.asScala
    def framingRenderer: akka.stream.scaladsl.Flow[ByteString, ByteString, NotUsed] = entityStreamingSupport.getFramingRenderer.asScala
    override def withSupported(range: akka.http.javadsl.model.ContentTypeRange): akka.http.scaladsl.common.EntityStreamingSupport =
      EntityStreamingSupportDelegate(entityStreamingSupport.withSupported(range))
    override def withContentType(contentType: akka.http.javadsl.model.ContentType): akka.http.scaladsl.common.EntityStreamingSupport =
      EntityStreamingSupportDelegate(entityStreamingSupport.withContentType(contentType))

    def parallelism: Int   = entityStreamingSupport.parallelism
    def unordered: Boolean = entityStreamingSupport.unordered
    def withParallelMarshalling(parallelism: Int, unordered: Boolean): akka.http.scaladsl.common.EntityStreamingSupport =
      EntityStreamingSupportDelegate(entityStreamingSupport.withParallelMarshalling(parallelism, unordered))
  }
}

/**
 * [[cloudflow.akkastream.ServerStreamletLogic]] for accepting HTTP requests.
 * Requires a `Server` to be passed in when it is created.
 * [[cloudflow.akkastream.AkkaServerStreamlet]] extends [[cloudflow.akkastream.Server]], which can be used for this purpose.
 * When you define the logic inside the streamlet, you can just pass in `this`:
 *
 * [[HttpServerLogic]] also predefined logics (`HttpServerLogic.createDefault` and `HttpServerLogic.createDefaultStreaming`)
 * for accepting, transcoding, and writing to an outlet.
 *
 * {{{
 * class TestHttpServer extends AkkaServerStreamlet {
 *   AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out",  d -> d.name(), Data.class);
 *   Unmarshaller<ByteString, Data> fbu = Jackson.byteStringUnmarshaller(Data.class);
 *
 *    public StreamletShape shape() {
 *      return StreamletShape.createWithOutlets(outlet);
 *    }
 *
 *    public HttpServerLogic createLogic() {
 *      return new HttpServerLogic(this, getStreamletContext()) {
 *        Route createRoute() {
 *          // define HTTP route here
 *        }
 *      }
 *    }
 *  }
 * }}}
 *
 */
abstract class HttpServerLogic(
    server: Server,
    context: AkkaStreamletContext
) extends akkastream.util.scaladsl.HttpServerLogic(server)(context) {

  /**
   * Override this method to define the HTTP route that this HttpServerLogic will use.
   * @return the Route that will be used to handle HTTP requests.
   */
  def createRoute(): akka.http.javadsl.server.Route
  override def route(): Route = createRoute().asScala
}
