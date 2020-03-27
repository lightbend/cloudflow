package com.example

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.spark._
import cloudflow.spark.sql.SQLImplicits._

import org.apache.spark.sql.streaming.OutputMode

//tag::processor[]
// create Spark Streamlet
class SparkProcessor extends SparkStreamlet {
  val in    = AvroInlet[Data]("in")
  val out   = AvroOutlet[Data]("out", _.id.toString)
  val shape = StreamletShape(in, out)

  override def createLogic() = new SparkStreamletLogic {
    override def buildStreamingQueries = {
      val dataset   = readStream(in)
      val outStream = dataset.filter(_.id % 2 == 0)
      val query     = writeStream(outStream, out, OutputMode.Append)
      query.toQueryExecution
    }
  }
}
//end::processor[]
