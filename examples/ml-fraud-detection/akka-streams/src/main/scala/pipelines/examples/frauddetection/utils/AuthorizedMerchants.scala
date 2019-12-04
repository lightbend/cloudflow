package pipelines.examples.frauddetection.utils

import scala.concurrent._
import scala.concurrent.duration._

import akka.NotUsed
import akka.actor.ActorSystem

import akka.http.scaladsl.Http
import akka.http.scaladsl.common._
import akka.http.scaladsl.model._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.unmarshalling._

import akka.stream._
import akka.stream.scaladsl.Source

import spray.json._

final case class Merchant(id: String)

object Merchant extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val merchantFormat: RootJsonFormat[Merchant] = jsonFormat(Merchant.apply, "merchant")
}

object AuthorizedMerchants {
  //import Merchant._
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  def getMerchants(authorizedMerchantsUri: String) = { //(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): Future[Vector[Merchant]] = {
    //    for {
    //      response ← Http().singleRequest(HttpRequest(uri = authorizedMerchantsUri))
    //      nolimit = response.withEntity(response.entity.withoutSizeLimit)
    //      source ← Unmarshal(nolimit).to[Source[Merchant, NotUsed]]
    //      merchants ← source.runFold(Vector.empty[Merchant]) { (acc, merchant) ⇒ acc :+ merchant }
    //    } yield merchants
    Future.successful(Vector.empty)
  }
  def getMerchantIds(authorizedMerchantsUri: String) = { //(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): Vector[String] = {
    Vector.empty //scala.concurrent.Await.result(getMerchants(authorizedMerchantsUri).map(_.map(_.time)), 5 minutes)
  }
}
