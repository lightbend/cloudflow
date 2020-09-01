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

import akka.annotation.ApiMayChange
import akka.grpc.scaladsl.ServiceHandler

import scala.collection.immutable
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.RouteDirectives
import cloudflow.akkastream.{ AkkaStreamletContext, Server }

import scala.concurrent.Future

@ApiMayChange
abstract class GrpcServerLogic(server: Server)(implicit context: AkkaStreamletContext) extends HttpServerLogic(server) {
  def handlers(): immutable.Seq[PartialFunction[HttpRequest, Future[HttpResponse]]]

  override def route(): Route = RouteDirectives.handle(ServiceHandler.concatOrNotFound(handlers(): _*))
}
