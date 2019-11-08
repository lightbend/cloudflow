package sensordata

import cloudflow.streamlets._

trait ConfigParams {
  val LogLevel = RegExpConfigParameter(
    "log-level",
    "Provide one of the following log levels, debug, info, warning or error",
    "^debug|info|warning|error$",
    Some("debug")
  )

  /*
  val InfluxDBActive = BooleanConfigParameter(
    key = "influxDBActive",
    description = "",
    defaultValue = Some(true)
  )
  */
}

