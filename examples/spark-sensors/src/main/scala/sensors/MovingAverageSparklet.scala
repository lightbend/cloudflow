package sensors

import cloudflow.streamlets.StreamletShape

import cloudflow.streamlets.avro._
import cloudflow.spark.{ SparkStreamletLogic, SparkStreamlet }

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.TimestampType
import cloudflow.spark.sql.SQLImplicits._
import org.apache.spark.sql.streaming.OutputMode

class MovingAverageSparklet extends SparkStreamlet {

  val in = AvroInlet[Data]("in")
  val out = AvroOutlet[Agg]("out", _.src)
  val shape = StreamletShape(in, out)

  override def createLogic() = new SparkStreamletLogic {
    override def buildStreamingQueries = {
      val dataset = readStream(in)
      val outStream = process(dataset)
      writeStream(outStream, out, OutputMode.Append).toQueryExecution
    }

    private def process(inDataset: Dataset[Data]): Dataset[Agg] = {
      val query = inDataset
        .withColumn("ts", $"timestamp".cast(TimestampType))
        .withWatermark("ts", "1 minutes")
        .groupBy(window($"ts", "1 minute", "30 seconds"), $"src", $"gauge").agg(avg($"value") as "avg")
      query.select($"src", $"gauge", $"avg" as "value").as[Agg]
    }
  }

}
