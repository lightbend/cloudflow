package sensordata

object SensorDataUtils {
  def isValidMetric(m: Metric) = m.value >= 0.0
}
