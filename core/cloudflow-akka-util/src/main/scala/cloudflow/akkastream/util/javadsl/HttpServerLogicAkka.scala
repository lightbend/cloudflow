/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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
 * Creates default [[HttpServerLogicAkka]]s that can be used to write data to an outlet that has been received by PUT or POST requests.
 */
object HttpServerLogicAkka {
  /**
   * Creates a HttpServerLogic that receives POST or PUT requests, unmarshals using the `fromByteStringUnmarshaller` and
   * writes the data to the providec `outlet`.
   */
  final def createDefault[Out](
      server: Server,
      outlet: CodecOutlet[Out],
      fromByteStringUnmarshaller: Unmarshaller[ByteString, Out],
      context: AkkaStreamletContext
  ) = {
    new HttpServerLogicAkka(server, outlet, fromByteStringUnmarshaller, context) {
      final override def createRoute(sinkRef: WritableSinkRef[Out]): akka.http.javadsl.server.Route = {
        RouteAdapter.asJava(akkastream.util.scaladsl.HttpServerLogicAkka.defaultRoute(sinkRef))
      }
    }
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
  ) = new StreamingHttpServerLogicAkka(server, outlet, fromByteStringUnmarshaller, ess, context) {
    implicit val fbs = fromByteStringUnmarshaller.asScala
    implicit val essScala = EntityStreamingSupportDelegate(ess)
    final override def createRoute(sinkRef: WritableSinkRef[Out]): akka.http.javadsl.server.Route = {
      RouteAdapter.asJava(akkastream.util.scaladsl.HttpServerLogicAkka.defaultRoute(sinkRef))
    }
  }
}

/**
 * Accepts and transcodes HTTP requests, then writes the transcoded data to the outlet.
 * By default this `HttpServerLogic` accepts PUT or POST requests containing entities that can be unmarshalled using the FromByteStringUnmarshaller.
 * Requires a `Server` to be passed in when it is created.
 * [[AkkaServerStreamlet]] extends [[Server]], which can be used for this purpose.
 * When you define the logic inside the streamlet, you can just pass in `this`:
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
 *      return HttpServerLogic.createDefault(this, outlet, fbu, getStreamletContext());
 *    }
 *  }
 * }}}
 *
 */
abstract class HttpServerLogicAkka[Out](
    server: Server,
    outlet: CodecOutlet[Out],
    fbu: Unmarshaller[ByteString, Out],
    context: AkkaStreamletContext
) extends akkastream.util.scaladsl.HttpServerLogicAkka(server, outlet)(context, fbu.asScala) {
  def createRoute(sinkRef: WritableSinkRef[Out]): akka.http.javadsl.server.Route
  override def route(sinkRef: WritableSinkRef[Out]): Route = createRoute(sinkRef).asScala
}

/**
 * Accepts and transcodes HTTP requests that contains framed elements, then writes the transcoded data to the outlet.
 * You need to supply an `EntityStreamingSupport` which
 * allows rendering and receiving incoming ``Source[T, _]`` from HTTP entities.
 *
 * Requires a `Server` to be passed in when it is created.
 * [[AkkaServerStreamlet]] extends [[Server]], which can be used for this purpose.
 * When you define the logic inside the streamlet, you can just pass in `this`:
 *
 * The elements in the source are unmarshalled using the `FromByteStringUnmarshaller`:
 * {{{
 * class TestHttpServer extends AkkaServerStreamlet {
 *   AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out",  d -> d.name(), Data.class);
 *   Unmarshaller<ByteString, Data> fbu = Jackson.byteStringUnmarshaller(Data.class);
 *   EntityStreamingSupport ess = EntityStreamingSupport.json();
 *
 *    public StreamletShape shape() {
 *      return StreamletShape.createWithOutlets(outlet);
 *    }
 *
 *    public HttpServerLogic createLogic() {
 *      return HttpServerLogic.createDefaultStreaming(this, outlet, fbu, ess, getStreamletContext());
 *    }
 *  }
 * }}}
 */
abstract class StreamingHttpServerLogicAkka[Out](
    server: Server,
    outlet: CodecOutlet[Out],
    fromByteStringUnmarshaller: Unmarshaller[ByteString, Out],
    val entityStreamingSupport: EntityStreamingSupport,
    context: AkkaStreamletContext
) extends HttpServerLogicAkka(server, outlet, fromByteStringUnmarshaller, context) {
  def createRoute(sinkRef: WritableSinkRef[Out]): akka.http.javadsl.server.Route
  override def route(sinkRef: WritableSinkRef[Out]): Route = createRoute(sinkRef).asScala
}

case class EntityStreamingSupportDelegate(entityStreamingSupport: akka.http.javadsl.common.EntityStreamingSupport) extends akka.http.scaladsl.common.EntityStreamingSupport {
  def supported: akka.http.scaladsl.model.ContentTypeRange = {
    entityStreamingSupport.supported.asInstanceOf[akka.http.scaladsl.model.ContentTypeRange]
  }
  def contentType: akka.http.scaladsl.model.ContentType = {
    entityStreamingSupport.contentType.asInstanceOf[akka.http.scaladsl.model.ContentType]
  }
  def framingDecoder: akka.stream.scaladsl.Flow[ByteString, ByteString, NotUsed] = entityStreamingSupport.getFramingDecoder.asScala
  def framingRenderer: akka.stream.scaladsl.Flow[ByteString, ByteString, NotUsed] = entityStreamingSupport.getFramingRenderer.asScala
  override def withSupported(range: akka.http.javadsl.model.ContentTypeRange): akka.http.scaladsl.common.EntityStreamingSupport = EntityStreamingSupportDelegate(entityStreamingSupport.withSupported(range))
  override def withContentType(contentType: akka.http.javadsl.model.ContentType): akka.http.scaladsl.common.EntityStreamingSupport = EntityStreamingSupportDelegate(entityStreamingSupport.withContentType(contentType))

  def parallelism: Int = entityStreamingSupport.parallelism
  def unordered: Boolean = entityStreamingSupport.unordered
  def withParallelMarshalling(parallelism: Int, unordered: Boolean): akka.http.scaladsl.common.EntityStreamingSupport =
    EntityStreamingSupportDelegate(entityStreamingSupport.withParallelMarshalling(parallelism, unordered))
}
