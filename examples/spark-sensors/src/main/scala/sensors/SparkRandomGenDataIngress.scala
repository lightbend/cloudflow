package sensors

import java.sql.Timestamp

import scala.util.Random

import cloudflow.streamlets.{ IntegerConfigParameter, StreamletShape }
import cloudflow.streamlets.avro._
import cloudflow.spark.{ SparkStreamlet, SparkStreamletLogic }
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.streaming.OutputMode

import cloudflow.spark.sql.SQLImplicits._

case class Rate(timestamp: Timestamp, value: Long)

class SparkRandomGenDataIngress extends SparkStreamlet {
  val out = AvroOutlet[Data]("out", d ⇒ d.src)
  val shape = StreamletShape(out)

  val RecordsPerSecond = IntegerConfigParameter(
    "records-per-second",
    "Records per second to produce.",
    Some(50))

  override def configParameters = Vector(RecordsPerSecond)

  override def createLogic() = new SparkStreamletLogic {

    override def buildStreamingQueries = {
      writeStream(process, out, OutputMode.Append).toQueryExecution
    }

    private def process: Dataset[Data] = {

      val recordsPerSecond = context.streamletConfig.getInt(RecordsPerSecond.key)

      val gaugeGen: () ⇒ String = () ⇒ if (Random.nextDouble() < 0.5) "oil" else "gas"

      val rateStream = session.readStream
        .format("rate")
        .option("rowsPerSecond", recordsPerSecond)
        .load()
        .as[Rate]

      rateStream.map {
        case Rate(timestamp, value) ⇒ Data(s"src-${value % 100}", timestamp.getTime, gaugeGen(), Random.nextDouble() * value)
      }
    }
  }
}
