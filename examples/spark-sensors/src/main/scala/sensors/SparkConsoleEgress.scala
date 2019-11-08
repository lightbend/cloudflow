package sensors

import cloudflow.streamlets.StreamletShape

import cloudflow.streamlets.avro._
import cloudflow.spark.{ SparkStreamletLogic, SparkStreamlet }
import cloudflow.spark.sql.SQLImplicits._
import org.apache.spark.sql.streaming.OutputMode

class SparkConsoleEgress extends SparkStreamlet {
  val in = AvroInlet[Agg]("in")
  val shape = StreamletShape(in)

  override def createLogic() = new SparkStreamletLogic {
    //tag::docs-checkpointDir-example[]
    override def buildStreamingQueries = {
      readStream(in).writeStream
        .format("console")
        .option("checkpointLocation", context.checkpointDir("console-egress"))
        .outputMode(OutputMode.Append())
        .start()
        .toQueryExecution
    }
    //end::docs-checkpointDir-example[]
  }
}
