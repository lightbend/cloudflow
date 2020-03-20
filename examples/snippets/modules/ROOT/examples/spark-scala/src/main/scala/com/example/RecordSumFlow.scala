package com.example

import cloudflow.streamlets._
import cloudflow.spark._
import cloudflow.streamlets.avro._
import cloudflow.spark.sql.SQLImplicits._

import cloudflow.sparkdoc.Data
import org.apache.spark.sql.streaming.OutputMode

object RecordSumFlow extends SparkStreamlet {

  val recordsInWindowParameter = IntegerConfigParameter(
    "records-in-window",
    "This value describes how many records of data should be processed together, default 64 records",
    Some(64)
  )

  override def configParameters = Vector(recordsInWindowParameter)

  val in    = AvroInlet[Data]("in")
  val out   = AvroOutlet[Data]("out", _.key)
  val shape = StreamletShape(in, out)

  override def createLogic() = new SparkStreamletLogic {
    override def buildStreamingQueries = {
      val dataset   = readStream(in)
      val outStream = dataset.filter(_.value % 2 == 0)
      val query     = writeStream(outStream, out, OutputMode.Append)
      query.toQueryExecution
    }
  }
}
