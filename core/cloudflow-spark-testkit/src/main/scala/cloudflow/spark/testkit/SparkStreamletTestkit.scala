/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.spark.testkit

import scala.annotation.varargs
import scala.concurrent.duration.Duration

import com.typesafe.config._
import org.apache.spark.sql.{ Encoder, SparkSession }
import org.apache.spark.sql.execution.streaming.MemoryStream

import cloudflow.spark.SparkStreamlet
import cloudflow.streamlets._
import scala.concurrent.Await

object ConfigParameterValue {
  def apply(configParameter: ConfigParameter, value: String): ConfigParameterValue =
    ConfigParameterValue(configParameter.key, value)

  // Java API
  def create(configParameter: ConfigParameter, value: String): ConfigParameterValue =
    ConfigParameterValue(configParameter.key, value)
}
final case class ConfigParameterValue private (configParameterKey: String, value: String)

/**
 * Testkit for testing Spark streamlets.
 *
 * The steps to write a test using the testkit are:
 *
 *   1. Create the test class and extend it with `SparkScalaTestSupport`
 *   1. Create the Spark streamlet testkit instance
 *   1. Create the Spark streamlet
 *   1. Setup inlet tap on inlet port
 *   1. Setup outlet tap on outlet port
 *   1. Build input data and send to inlet tap
 *   1. Run the test
 *   1. Get data from outlet and assert
 *
 * {{{
 * // 1. Create the test class and extend it with `SparkScalaTestSupport`
 * class MySparkStreamletSpec extends SparkScalaTestSupport {
 *
 *  "SparkProcessor" should {
 *    "process streaming data" in {
 *
 *      // 2. Create Spark streamlet testkit instance
 *      val testKit = SparkStreamletTestkit(session)
 *
 *      // 3. Create spark streamlet
 *      val processor = new SparkProcessor[Data, Simple] {
 *        override def createLogic(): ProcessorLogic[Data, Simple] = new ProcessorLogic[Data, Simple](OutputMode.Append) {
 *          override def process(inDataset: Dataset[Data]): Dataset[Simple] =
 *            inDataset.select($"name").as[Simple]
 *        }
 *      }
 *
 *      // 4. Setup inlet(s) tap on inlet port(s)
 *      val in: SparkInletTap[Data] = inletAsTap[Data](processor.shape.inlet)
 *
 *      // 5. Setup outlet tap(s) on outlet port(s)
 *      val out: SparkOutletTap[Simple] = outletAsTap[Simple](processor.shape.outlet)
 *
 *      // 6. Prepare input data and send it to the inlet tap(s)
 *      val data = (1 to 10).map(i ⇒ Data(i, s"name\$i"))
 *      in.addData(data)
 *
 *      // 7. Run the test
 *      run(processor, Seq(in), Seq(out), 2.seconds)
 *
 *      // 8. Get data from outlet tap(s) and assert
 *      val results = out.asCollection(session)
 *
 *      results should contain(Simple("name1"))
 *    }
 *  }
 * }
 * }}}
 *
 * Note: Every test is executed against a `SparkSession` which gets created and removed as part of the test
 * lifecycle methods.
 */
final case class SparkStreamletTestkit(session: SparkSession, config: Config = ConfigFactory.empty) {

  implicit lazy val sqlCtx = session.sqlContext

  /**
   * Adding configuration parameters and their values to the configuration used in the test.
   *
   * [[ConfigParameterValue]] takes a [[cloudflow.streamlets.ConfigParameter ConfigParameter]] and a string containing the value of the parameter.
   */
  @varargs
  def withConfigParameterValues(configParameterValues: ConfigParameterValue*): SparkStreamletTestkit = {
    val parameterValueConfig = ConfigFactory.parseString(
      configParameterValues
        .map(parameterValue ⇒ s"cloudflow.streamlets.streamlet-under-test.${parameterValue.configParameterKey} = ${parameterValue.value}")
        .mkString("\n")
    )
    this.copy(config = config.withFallback(parameterValueConfig).resolve)
  }

  implicit class InletTapOps[T: Encoder](inlet: CodecInlet[T]) {
    def inletAsTap = SparkInletTap(inlet.name, MemoryStream[T])
  }

  implicit class OutletTapOps[T: Encoder](outlet: CodecOutlet[T]) {
    def outletAsTap = SparkOutletTap(outlet.name, "testQuery" + scala.util.Random.nextInt(999999))
  }

  // tap inlet port as a MemoryStream
  def inletAsTap[In: Encoder](in: CodecInlet[In]) = in.inletAsTap

  // tap outlet port for query result
  // outputMode needs to be OutputMode.Complete for queries with aggregation operators
  def outletAsTap[Out: Encoder](out: CodecOutlet[Out]) = out.outletAsTap

  /**
   * Runs the `sparkStreamlet` using `inletTaps` as the sources and `outletTaps` as the sinks. Each `inletTap` abstracts
   * a `MemoryStream` and an inlet port, where the test input data gets added. The `outletTap` returns a port and
   * a query name, which gives a handle to the Spark `StreamingQuery` name that gets executed.
   *
   * @param sparkStreamlet the Sparklet to run
   * @param inletTaps the collection of inlet streams and ports
   * @param outletTaps the collection of outlet query names and ports
   * @param duration the duration to run the query
   *
   * @return Unit
   */
  def run(
      sparkStreamlet: SparkStreamlet,
      inletTaps: Seq[SparkInletTap[_]],
      outletTaps: Seq[SparkOutletTap[_]],
      duration: Duration
  ): Unit = {
    val ctx = new TestSparkStreamletContext("streamlet-under-test", session, inletTaps, outletTaps, config)
    doRun(ctx, sparkStreamlet, duration)
  }

  /**
   * Runs the `sparkStreamlet` using `inletTap` as the source and `outletTap` as the sink. Each `inletTap` abstracts
   * a `MemoryStream` and an inlet port, where the test input data gets added. The `outletTap` returns a port and
   * a query name, which gives a handle to the Spark `StreamingQuery` name that gets executed.
   *
   * @param sparkStreamlet the Sparklet to run
   * @param inletTap the inlet stream and port
   * @param outletTap the outlet query and port
   * @param duration the duration to run the query
   *
   * @return Unit
   */
  def run(
      sparkStreamlet: SparkStreamlet,
      inletTap: SparkInletTap[_],
      outletTap: SparkOutletTap[_],
      duration: Duration
  ): Unit = {
    val ctx = new TestSparkStreamletContext("streamlet-under-test", session, Seq(inletTap), Seq(outletTap), config)
    doRun(ctx, sparkStreamlet, duration)
  }

  /**
   * Runs the `sparkStreamlet` using `inletTaps` as the sources and `outletTap` as the sink. Each `inletTap` abstracts
   * a `MemoryStream` and an inlet port, where the test input data gets added. The `outletTap` returns a port and
   * a query name, which gives a handle to the Spark `StreamingQuery` name that gets executed.
   *
   * @param sparkStreamlet the Sparklet to run
   * @param inletTaps the collection of inlet streams and ports
   * @param outletTap the outlet query and port
   * @param duration the duration to run the query
   *
   * @return Unit
   */
  def run(
      sparkStreamlet: SparkStreamlet,
      inletTaps: Seq[SparkInletTap[_]],
      outletTap: SparkOutletTap[_],
      duration: Duration
  ): Unit = {
    val ctx = new TestSparkStreamletContext("streamlet-under-test", session, inletTaps, Seq(outletTap), config)
    doRun(ctx, sparkStreamlet, duration)
  }

  /**
   * Runs the `sparkStreamlet` using `inletTap` as the source and `outletTaps` as the sinks. Each `inletTap` abstracts
   * a `MemoryStream` and an inlet port, where the test input data gets added. The `outletTap` returns a port and
   * a query name, which gives a handle to the Spark `StreamingQuery` name that gets executed.
   *
   * @param sparkStreamlet the Sparklet to run
   * @param inletTap the inlet stream and port
   * @param outletTaps the collection of outlet query names and ports
   * @param duration the duration to run the query
   *
   * @return Unit
   */
  def run(
      sparkStreamlet: SparkStreamlet,
      inletTap: SparkInletTap[_],
      outletTaps: Seq[SparkOutletTap[_]],
      duration: Duration
  ): Unit = {
    val ctx = new TestSparkStreamletContext("streamlet-under-test", session, Seq(inletTap), outletTaps, config)
    doRun(ctx, sparkStreamlet, duration)
  }

  private[testkit] def doRun(
      ctx: TestSparkStreamletContext,
      sparkStreamlet: SparkStreamlet,
      duration: Duration
  ): Unit = {
    val t0 = System.currentTimeMillis()
    val queryExecution = sparkStreamlet.setContext(ctx).run(ctx.config)
    while (session.streams.active.nonEmpty && (System.currentTimeMillis()-t0)<duration.toMillis ) {
      session.streams.awaitAnyTermination(duration.toMillis)
      session.streams.resetTerminated()
    }
    queryExecution.stop()
    Await.result(queryExecution.completed, duration)
  }
}

case class SparkInletTap[T: Encoder](
    portName: String,
    instream: MemoryStream[T]
) {
  // add data to memory stream
  def addData(data: Seq[T]) = instream.addData(data)
}

case class SparkOutletTap[T: Encoder](
    portName: String,
    queryName: String
) {
  // get results from memory sink
  def asCollection(session: SparkSession): Seq[T] = session.sql(s"select * from $queryName").as[T].collect()
}
