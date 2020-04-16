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

import java.util.UUID

import scala.annotation.varargs
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import com.typesafe.config._
import org.apache.spark.sql.{ Encoder, SparkSession }
import org.apache.spark.sql.execution.streaming.MemoryStream
import cloudflow.spark.SparkStreamlet
import cloudflow.streamlets._
import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.StreamingQueryListener.{ QueryProgressEvent, QueryStartedEvent, QueryTerminatedEvent }

import scala.concurrent.{ Await, ExecutionContext, Future, Promise }

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
 *      run(processor, Seq(in), Seq(out))
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
final case class SparkStreamletTestkit(session: SparkSession, config: Config = ConfigFactory.empty, maxDuration: Duration = 30.seconds) {

  implicit lazy val sqlCtx = session.sqlContext
  import ExecutionContext.Implicits.global
  val TestStreamletName = "streamlet-under-test"

  /**
   * Adding configuration parameters and their values to the configuration used in the test.
   *
   * [[ConfigParameterValue]] takes a [[cloudflow.streamlets.ConfigParameter ConfigParameter]] and a string containing the value of the parameter.
   */
  @varargs
  def withConfigParameterValues(configParameterValues: ConfigParameterValue*): SparkStreamletTestkit = {
    val parameterValueConfig = ConfigFactory.parseString(
      configParameterValues
        .map(parameterValue ⇒ s"cloudflow.streamlets.$TestStreamletName.${parameterValue.configParameterKey} = ${parameterValue.value}")
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
   * Runs the `sparkStreamlet` using `inletTaps` as the sources and `outletTaps` as the sinks.
   * Each `inletTap` abstracts a `MemoryStream` and an inlet port, where the test input data gets added.
   * The `outletTap` returns a port and a query name, which gives a handle to the Spark `StreamingQuery` name that gets executed.
   *
   * @param sparkStreamlet the Sparklet to run
   * @param inletTaps the collection of inlet streams and ports
   * @param outletTaps the collection of outlet query names and ports
   *
   * @return Unit
   */
  def run(
      sparkStreamlet: SparkStreamlet,
      inletTaps: Seq[SparkInletTap[_]],
      outletTaps: Seq[SparkOutletTap[_]]
  ): ExecutionReport = {
    val ctx = new TestSparkStreamletContext(TestStreamletName, session, inletTaps, outletTaps, config)
    doRun(ctx, sparkStreamlet)
  }

  /**
   * Runs the `sparkStreamlet` using `inletTap` as the source and `outletTap` as the sink. Each `inletTap` abstracts
   * a `MemoryStream` and an inlet port, where the test input data gets added. The `outletTap` returns a port and
   * a query name, which gives a handle to the Spark `StreamingQuery` name that gets executed.
   *
   * @param sparkStreamlet the Sparklet to run
   * @param inletTap the inlet stream and port
   * @param outletTap the outlet query and port
   *
   * @return Unit
   */
  def run(
      sparkStreamlet: SparkStreamlet,
      inletTap: SparkInletTap[_],
      outletTap: SparkOutletTap[_]
  ): ExecutionReport = {
    val ctx = new TestSparkStreamletContext(TestStreamletName, session, Seq(inletTap), Seq(outletTap), config)
    doRun(ctx, sparkStreamlet)
  }

  /**
   * Runs the `sparkStreamlet` using `inletTaps` as the sources and `outletTap` as the sink. Each `inletTap` abstracts
   * a `MemoryStream` and an inlet port, where the test input data gets added. The `outletTap` returns a port and
   * a query name, which gives a handle to the Spark `StreamingQuery` name that gets executed.
   *
   * @param sparkStreamlet the Sparklet to run
   * @param inletTaps the collection of inlet streams and ports
   * @param outletTap the outlet query and port
   *
   * @return Unit
   */
  def run(
      sparkStreamlet: SparkStreamlet,
      inletTaps: Seq[SparkInletTap[_]],
      outletTap: SparkOutletTap[_]
  ): ExecutionReport = {
    val ctx = new TestSparkStreamletContext(TestStreamletName, session, inletTaps, Seq(outletTap), config)
    doRun(ctx, sparkStreamlet)
  }

  /**
   * Runs the `sparkStreamlet` using `inletTap` as the source and `outletTaps` as the sinks. Each `inletTap` abstracts
   * a `MemoryStream` and an inlet port, where the test input data gets added. The `outletTap` returns a port and
   * a query name, which gives a handle to the Spark `StreamingQuery` name that gets executed.
   *
   * @param sparkStreamlet the Sparklet to run
   * @param inletTap the inlet stream and port
   * @param outletTaps the collection of outlet query names and ports
   *
   * @return Unit
   */
  def run(
      sparkStreamlet: SparkStreamlet,
      inletTap: SparkInletTap[_],
      outletTaps: Seq[SparkOutletTap[_]]
  ): ExecutionReport = {
    val ctx = new TestSparkStreamletContext(TestStreamletName, session, Seq(inletTap), outletTaps, config)
    doRun(ctx, sparkStreamlet)
  }

  private[testkit] def doRun(
      ctx: TestSparkStreamletContext,
      sparkStreamlet: SparkStreamlet
  ): ExecutionReport = {
    val queryMonitor = new QueryExecutionMonitor()
    ctx.session.streams.addListener(queryMonitor)
    val queryExecution = sparkStreamlet.setContext(ctx).run(ctx.config)
    val gotData        = queryMonitor.waitForData().andThen { case _ => queryExecution.stop() }

    Await.result(gotData, maxDuration)
    queryMonitor.executionReport
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
case class ExecutionReport(totalRows: Long, totalQueries: Int, failures: Seq[String]) {
  override def toString: String =
    s"total rows: [$totalRows], total queries: [$totalQueries], failures: [${failures.mkString(",")}]"
}

class QueryExecutionMonitor()(implicit ec: ExecutionContext) extends StreamingQueryListener {
  @volatile var status: Map[UUID, QueryState] = Map()
  @volatile var dataRows: Map[UUID, Long]     = Map()

  val dataAvailable: Promise[Long] = Promise()

  sealed trait QueryState
  case object Started                              extends QueryState
  case class Terminated(exception: Option[String]) extends QueryState

  def hasData =
    dataRows.nonEmpty && dataRows.forall { case (_, rows) => rows > 0 }

  def executionReport: ExecutionReport = {
    val exceptions = status.values.collect { case Terminated(Some(reason)) => reason }
    ExecutionReport(dataRows.values.sum, dataRows.keys.size, exceptions.toSeq)
  }

  def waitForData(): Future[Long] =
    dataAvailable.future

  override def onQueryStarted(queryStarted: QueryStartedEvent): Unit = {
    dataRows = dataRows + (queryStarted.id -> 0L)
    status = status + (queryStarted.id     -> Started)
  }

  override def onQueryTerminated(queryTerminated: QueryTerminatedEvent): Unit =
    status = status + (queryTerminated.id -> Terminated(queryTerminated.exception))

  override def onQueryProgress(queryProgress: QueryProgressEvent): Unit = {
    val id   = queryProgress.progress.id
    val rows = queryProgress.progress.numInputRows
    dataRows = dataRows + dataRows.get(id).map(v => id -> (v + rows)).getOrElse(id -> rows)
    if (hasData & !dataAvailable.isCompleted) {
      dataAvailable.trySuccess(dataRows.values.sum)
    }
  }

}
