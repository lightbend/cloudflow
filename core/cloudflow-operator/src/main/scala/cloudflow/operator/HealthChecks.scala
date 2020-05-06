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

package cloudflow.operator

import scala.concurrent._
import scala.util._
import akka.actor._
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._

object HealthChecks {
  def serve(settings: Settings)(implicit system: ActorSystem, ec: ExecutionContext) =
    Http()
      .bindAndHandle(
        route,
        settings.api.bindInterface,
        settings.api.bindPort
      )
      .onComplete {
        case Success(serverBinding) ⇒
          system.log.info(s"Bound to ${serverBinding.localAddress}.")
        case Failure(e) ⇒
          system.log.error(e, s"Failed to bind.")
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
