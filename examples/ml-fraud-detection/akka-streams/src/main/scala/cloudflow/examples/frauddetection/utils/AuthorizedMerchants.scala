package cloudflow.examples.frauddetection.utils

import scala.concurrent._

import akka.http.scaladsl.common._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport

import spray.json._

final case class Merchant(id: String)

object Merchant extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val merchantFormat: RootJsonFormat[Merchant] = jsonFormat(Merchant.apply, "merchant")
}

object AuthorizedMerchants {
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  def getMerchants(authorizedMerchantsUri: String) = {
    Future.successful(Vector.empty)
  }
  def getMerchantIds(authorizedMerchantsUri: String) = {
    Vector.empty
  }
}
