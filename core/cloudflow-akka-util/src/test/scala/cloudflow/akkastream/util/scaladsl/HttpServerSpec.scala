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

package cloudflow.akkastream.util.scaladsl

import scala.concurrent.duration._
import akka.actor._
import akka.stream.ActorMaterializer
import akka.http.scaladsl._
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._

import akka.testkit._
import com.typesafe.config._
import org.scalatest._
import org.scalatest.concurrent._

import spray.json._
import spray.json.DefaultJsonProtocol._

import org.scalatest.time._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.akkastream._
import cloudflow.akkastream.testdata._
import cloudflow.akkastream.testkit.scaladsl._

class HttpServerSpec extends WordSpec
  with MustMatchers with ScalaFutures with BeforeAndAfterEach with BeforeAndAfterAll {

  private implicit val system = ActorSystem("HttpServerSpec")
  private implicit val mat = ActorMaterializer()

  import system.dispatcher

  implicit val jsonformatData: RootJsonFormat[Data] = jsonFormat2(Data.apply)

  implicit override val patienceConfig = PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))
  val noMessageDuration = 100 millis

  "HttpServer" should {
    // CONNECT request is not allowed, possibly because of use of singleRequest, or absolute URI?
    // Removed for now. (The test responds with 400 Bad Request)
    // Error in log: Illegal request, responding with status '400 Bad Request': CONNECT requests are not supported: Rejecting CONNECT request to '/'
    List(GET, DELETE, HEAD, OPTIONS, PATCH).foreach { method ⇒
      s"reject ${method.value} requests when using the default route" in {
        startIngress()
        val request = HttpRequest(method, Uri(s"http://127.0.0.1:$port"), Nil, HttpEntity.Empty)
        val response = Http().singleRequest(request).futureValue
        response.status mustEqual StatusCodes.MethodNotAllowed
        out.probe.expectNoMessage(noMessageDuration)
      }
    }

    "accept marshallable data when using the default route" in {
      startIngress()
      val data = Data(1, "a")
      val request = Post(s"http://127.0.0.1:$port", data)
      val response = Http().singleRequest(request).futureValue
      response.status mustEqual StatusCodes.Accepted
      out.probe.expectMsg(("1", data))
      out.probe.expectMsg(Completed)
    }

    "reject POST requests without an entity when using the default route" in {
      startIngress()
      val request = Post(s"http://127.0.0.1:$port")
      val response = Http().singleRequest(request).futureValue
      response.status mustEqual StatusCodes.BadRequest
    }

    "accept data using a custom route" in {
      startIngress(customIngress)
      val data = Data(42, "a")
      val request = Put(s"http://127.0.0.1:$port", data)
      val response = Http().singleRequest(request).futureValue
      response.status mustEqual StatusCodes.OK
      out.probe.expectMsg(("42", data))
      out.probe.expectMsg(Completed)

      val badData = Data(1, "a")
      val badRequest = Put(s"http://127.0.0.1:$port", badData)
      val badResponse = Http().singleRequest(badRequest).futureValue
      badResponse.status mustEqual StatusCodes.BadRequest
      out.probe.expectNoMessage(noMessageDuration)
    }

    "reject wrong method in a custom route" in {
      startIngress(customIngress)
      val data = Data(42, "a")
      val wrongMethod = Post(s"http://127.0.0.1:$port", data)
      val wrongMethodResponse = Http().singleRequest(wrongMethod).futureValue
      wrongMethodResponse.status mustEqual StatusCodes.MethodNotAllowed
      out.probe.expectNoMessage(noMessageDuration)
    }
  }

  def customIngress = new AkkaServerStreamlet() {
    val outlet = AvroOutlet[Data]("out", _.id.toString)
    val shape = StreamletShape(outlet)

    override def createLogic = new HttpServerLogicAkka(this, outlet) {
      override def route(writer: WritableSinkRef[Data]): Route = {
        put {
          entity(as[Data]) { data ⇒
            if (data.id == 42) {
              onSuccess(writer.write(data)) { _ ⇒
                complete(StatusCodes.OK)
              }
            } else complete(StatusCodes.BadRequest)
          }
        }
      }
    }
  }

  var ref: StreamletExecution = _
  var port: Int = _
  var ingress: AkkaStreamlet = _
  var out: ProbeOutletTap[Data] = _

  val outlet = AvroOutlet[Data]("out", _.id.toString)
  def createDefaultIngress = new AkkaServerStreamlet() {
    val shape = StreamletShape(outlet)
    override def createLogic = HttpServerLogicAkka.default(this, outlet)
  }

  def startIngress(ingress: AkkaStreamlet = createDefaultIngress): Unit = {
    val testkit = AkkaStreamletTestKit(system, mat)

    out = testkit.outletAsTap(outlet)
    port = getFreePort()

    val config = ConfigFactory.parseString(s"""
          cloudflow.internal.server.container-port = $port
        """)

    ref = testkit.withConfig(config).run(ingress, List.empty, List(out))
    ref.ready.futureValue
  }

  override def afterEach(): Unit = {
    ref.stop().futureValue
  }

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  def getFreePort(): Int = {
    val socket = new java.net.ServerSocket(0)
    val port = socket.getLocalPort()
    socket.close()
    port
  }
}
