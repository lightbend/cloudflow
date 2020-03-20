package cloudflow.sparkdoc

import cloudflow.spark._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

import org.apache.spark.sql.functions._
import cloudflow.spark.sql.SQLImplicits._
import org.apache.spark.sql.types.TimestampType
import org.apache.spark.sql.streaming.OutputMode

//tag::spark-streamlet-example[]
class MovingAverageSparklet extends SparkStreamlet { // <1>

  val in    = AvroInlet[Data]("in")
  val out   = AvroOutlet[Data]("out", _.key)
  val shape = StreamletShape(in, out) // <2>

  override def createLogic() = new SparkStreamletLogic {
    override def buildStreamingQueries = { // <3>

      val groupedData = readStream(in) // <4>
        .withColumn("ts", $"timestamp".cast(TimestampType))
        .withWatermark("ts", "1 minutes")
        .groupBy(window($"ts", "1 minute", "30 seconds"), $"key")
        .agg(avg($"value").as("avg"))
      val query = groupedData.select($"key", $"avg".as("value")).as[Data]

      writeStream(query, out, OutputMode.Append).toQueryExecution
    }
  }
}
//end::spark-streamlet-example[]
