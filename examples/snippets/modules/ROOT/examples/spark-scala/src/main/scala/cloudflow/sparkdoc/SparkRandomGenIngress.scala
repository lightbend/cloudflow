package cloudflow.sparkdoc

import scala.util.Random

import cloudflow.spark._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.spark.sql.SQLImplicits._

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.streaming.OutputMode
import java.sql.Timestamp

class SparkRandomGenDataIngress extends SparkStreamlet {
  val out   = AvroOutlet[Data]("out", d ⇒ d.key)
  val shape = StreamletShape(out)

  case class Rate(timestamp: Timestamp, value: Long)

  override def createLogic() = new SparkStreamletLogic {

    override def buildStreamingQueries =
      writeStream(process, out, OutputMode.Append).toQueryExecution

    private def process: Dataset[Data] = {

      val recordsPerSecond = 10

      val keyGen: () ⇒ String = () ⇒ if (Random.nextDouble() < 0.5) "keyOne" else "keyTwo"

      val rateStream = session.readStream
        .format("rate")
        .option("rowsPerSecond", recordsPerSecond)
        .load()
        .as[Rate]

      rateStream.map {
        case Rate(_, value) ⇒ Data(keyGen(), value.toInt)
      }
    }
  }
}