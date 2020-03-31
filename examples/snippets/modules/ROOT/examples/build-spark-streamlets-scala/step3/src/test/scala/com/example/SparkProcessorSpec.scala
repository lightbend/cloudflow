package com.example

//tag::imports[]
import scala.collection.immutable.Seq
import scala.concurrent.duration._

import cloudflow.spark.testkit._
import cloudflow.spark.sql.SQLImplicits._
//end::imports[]

//tag::test[]
class SparkProcessorSpec extends SparkScalaTestSupport { // 1. Extend SparkScalaTestSupport

  "SparkProcessor" should {

    // 2. Initialize the testkit
    val testkit = SparkStreamletTestkit(session)

    "process streaming data" in {

      // 3. create Spark streamlet
      val processor = new SparkProcessor()

      // 4. setup inlet tap on inlet port
      val in: SparkInletTap[Data] = testkit.inletAsTap[Data](processor.in)

      // 5. setup outlet tap on outlet port
      val out: SparkOutletTap[Data] = testkit.outletAsTap[Data](processor.out)

      // 6. build data and send to inlet tap
      val data = (1 to 10).map(i ⇒ Data(i, s"name$i"))
      in.addData(data)

      // 7. Run the streamlet using the testkit and the setup inlet taps and outlet probes
      testkit.run(processor, Seq(in), Seq(out), 2.seconds)

      // get data from outlet tap
      val results = out.asCollection(session)
      println("**************")
      println("Results from the test:" + results.mkString(","))
      println("**************")

      // 8. Assert that actual matches expectation
      results must contain(Data(2, "name2"))
      results.size must be(5)
    }
  }
}
//end::test[]
