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

import java.util.concurrent.CompletionStage
import java.util.{ List => JList }

import akka.annotation.ApiMayChange
import akka.japi.Function
import akka.grpc.javadsl.ServiceHandler
import akka.http.javadsl.model.{ HttpRequest, HttpResponse }
import akka.http.javadsl.server.{ Directives, Route }
import cloudflow.akkastream.{ AkkaStreamletContext, Server }

@ApiMayChange
abstract class GrpcServerLogic(server: Server, context: AkkaStreamletContext) extends HttpServerLogic(server, context) {
  def handlers(): JList[Function[HttpRequest, CompletionStage[HttpResponse]]]

  override def createRoute(): Route = {
    import scala.collection.JavaConverters._
    val handler = ServiceHandler.concatOrNotFound(handlers().asScala: _*)
    Directives.handle(request => handler(request))
  }
}
