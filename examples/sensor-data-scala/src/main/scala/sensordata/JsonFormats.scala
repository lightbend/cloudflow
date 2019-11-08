package sensordata

import java.time.Instant
import java.util.UUID

import scala.util.Try

import spray.json._

trait UUIDJsonSupport extends DefaultJsonProtocol {
  implicit object UUIDFormat extends JsonFormat[UUID] {
    def write(uuid: UUID) = JsString(uuid.toString)

    def read(json: JsValue): UUID = json match {
      case JsString(uuid) ⇒ Try(UUID.fromString(uuid)).getOrElse(deserializationError(s"Expected valid UUID but got '$uuid'."))
      case other          ⇒ deserializationError(s"Expected UUID as JsString, but got: $other")
    }
  }
}

trait InstantJsonSupport extends DefaultJsonProtocol {
  implicit object InstantFormat extends JsonFormat[Instant] {
    def write(instant: Instant) = JsNumber(instant.toEpochMilli)

    def read(json: JsValue): Instant = json match {
      case JsNumber(value) ⇒ Instant.ofEpochMilli(value.toLong)
      case other           ⇒ deserializationError(s"Expected Instant as JsNumber, but got: $other")
    }
  }
}

object MeasurementsJsonSupport extends DefaultJsonProtocol {
  implicit val measurementFormat = jsonFormat3(Measurements.apply)
}

object SensorDataJsonSupport extends DefaultJsonProtocol with UUIDJsonSupport with InstantJsonSupport {
  import MeasurementsJsonSupport._
  implicit val sensorDataFormat = jsonFormat3(SensorData.apply)
}
