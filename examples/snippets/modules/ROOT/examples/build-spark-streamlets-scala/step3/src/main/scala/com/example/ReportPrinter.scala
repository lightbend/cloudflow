package com.example

import org.apache.spark.sql.streaming._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.spark._

import cloudflow.spark.sql.SQLImplicits._

object ReportPrinter extends SparkStreamlet {
  // 1. Create inlets and outlets
  val in = AvroInlet[Report]("report-in")

  // 2. Define the shape of the streamlet
  override val shape = StreamletShape.withInlets(in)

  // 3. Override createLogic to provide StreamletLogic
  override def createLogic = new SparkStreamletLogic {
    // Define some formatting attributes
    val numRows  = 50
    val truncate = false

    override def buildStreamingQueries = {
      val inDataset = readStream(in)
      val query = inDataset.writeStream
        .format("console")
        .option("numRows", numRows)
        .option("truncate", truncate)
        .outputMode(OutputMode.Append())
        .start()
      query.toQueryExecution
    }
  }
}
