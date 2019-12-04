package pipelines.examples.frauddetection.process

import org.apache.spark.sql.functions._
import cloudflow.spark.sql.SQLImplicits._
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.TimestampType
import pipelines.examples.frauddetection.data.{ FraudReport, ScoredTransaction }
import cloudflow.spark.{ SparkStreamlet, SparkStreamletLogic, StreamletQueryExecution }
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.{ AvroInlet, AvroOutlet }

class FraudPerMerchantPerMonth extends SparkStreamlet {

  val transactionsGoInHere = AvroInlet[ScoredTransaction]("in")
  val neverendingInsightsComeOutHere = AvroOutlet[FraudReport]("out")

  val shape = StreamletShape(transactionsGoInHere, neverendingInsightsComeOutHere)

  override protected def createLogic(): SparkStreamletLogic = new SparkStreamletLogic() {
    override def buildStreamingQueries: StreamletQueryExecution = {
      val dataset = readStream(transactionsGoInHere)
      val outStream = process(dataset)
      writeStream(outStream, neverendingInsightsComeOutHere, OutputMode.Update()).toQueryExecution
    }

    private def process(inDataset: Dataset[ScoredTransaction]): Dataset[FraudReport] = {
      val query = inDataset
        .withColumn("ts", $"inputRecord.time".cast(TimestampType))
        .withWatermark("ts", "10 minutes")
        .groupBy(window($"ts", "60 minutes", "60 minutes"), $"inputRecord.merchantId")

      query.agg(
        min($"inputRecord.amount") as "amountMin",
        max($"inputRecord.amount") as "amountMax",
        avg($"inputRecord.amount") as "amountAvg"
      ).withColumn("year", year($"window.start"))
        .withColumn("month", month($"window.start"))
        .withColumn("day", dayofmonth($"window.start"))
        .as[FraudReport]
    }
  }
}
