package com.example

import cloudflow.spark.testkit._

class SampleSpec extends SparkScalaTestSupport {

  "An TestProcessor" should {

    //tag::config-value[]
    val testKit = SparkStreamletTestkit(session).withConfigParameterValues(ConfigParameterValue(RecordSumFlow.recordsInWindowParameter, "20"))
    //end::config-value[]

    "Allow for creating a 'flow processor'" in {
      val a = 1
      a must equal(1)
    }
  }
}
