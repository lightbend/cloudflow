import java.time.Instant

package object sensordata {
  implicit def toInstant(millis: Long): Instant = Instant.ofEpochMilli(millis)
}
