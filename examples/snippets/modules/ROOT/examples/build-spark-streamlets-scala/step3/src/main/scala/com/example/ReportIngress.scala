package com.example

import java.sql.Timestamp

import cloudflow.spark._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

import cloudflow.spark.sql.SQLImplicits._

import org.apache.spark.sql.streaming.OutputMode

case class Rate(timestamp: Timestamp, value: Long)

object ReportIngress extends SparkStreamlet {

  val out = AvroOutlet[Report]("out")

  override val shape = StreamletShape.withOutlets(out)

  override def createLogic(): SparkStreamletLogic = new SparkStreamletLogic {
    override def buildStreamingQueries = {
      val report = Report("abc", "test", "Just a test", List("ab", "bc"))

      val rateStream = session.readStream
        .format("rate")
        .option("rowsPerSecond", 1)
        .load()
        .as[Rate]

      val query = writeStream(rateStream.map(_ => report), out, OutputMode.Append())

      query.toQueryExecution
    }
  }
}
