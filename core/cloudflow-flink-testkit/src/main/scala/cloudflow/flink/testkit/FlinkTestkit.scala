/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.flink
package testkit

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

import org.apache.flink.streaming.api.scala._
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.datastream.{ DataStream ⇒ JDataStream }
import org.apache.flink.streaming.api.environment.{ StreamExecutionEnvironment ⇒ JStreamExecutionEnvironment }
import com.typesafe.config._

import cloudflow.flink.FlinkStreamlet
import cloudflow.streamlets._

/**
 * Testkit for testing Flink streamlets.
 *
 * The steps to write a test using the testkit are:
 *
 * 1. Create the Flink streamlet
 * 2. Setup inlet tap on inlet port with the input data
 * 3. Setup outlet tap on outlet port
 * 4. Run the test
 * 5. Get data from outlet and assert
 *
 * {{
 * "FlinkProcessor" should {
 *   "process streaming data" in {
 *      @transient lazy val env = StreamExecutionEnvironment.getExecutionEnvironment
 *
 *     object FlinkProcessor extends FlinkStreamlet {
 *       // Step 1: Define inlets and outlets. Note for the outlet you can specify
 *       //         the partitioner function explicitly or else `RoundRobinPartitioner`
 *       //         will be used
 *       val in = AvroInlet[Data]("in")
 *       val out = AvroOutlet[Simple]("out", _.getName())
 *
 *       // Step 2: Define the shape of the streamlet. In this example the streamlet
 *       //         has 1 inlet and 1 outlet
 *       val shape = StreamletShape(in, out)
 *
 *       // Step 3: Provide custom implementation of `FlinkStreamletLogic` that defines
 *       //         the behavior of the streamlet
 *       override def createLogic() = new FlinkStreamletLogic {
 *         override def buildExecutionGraph = {
 *           val ins: DataStream[Data] = readStream(in)
 *           val simples: DataStream[Simple] = ins.map(r ⇒ new Simple(r.getName()))
 *           writeStream(out, simples)
 *         }
 *       }
 *     }
 *
 *     // build data to send to inlet tap
 *     val data = (1 to 10).map(i ⇒ new Data(i, s"name$i"))
 *
 *     // setup inlet tap on inlet port and load input data
 *     val in: FlinkInletTap[Data] = inletAsTap[Data](FlinkProcessor.in,
 *       env.addSource(FlinkSource.CollectionSourceFunction(data)))
 *
 *     // setup outlet tap on outlet port
 *     val out: FlinkOutletTap[Simple] = outletAsTap[Simple](FlinkProcessor.out)
 *
 *     // run the streamlet
 *     run(FlinkProcessor, Seq(in), Seq(out), env)
 *
 *     // verify results
 *     TestFlinkStreamletContext.result should contain((new Simple("name1")).toString())
 *     TestFlinkStreamletContext.result.size should equal(10)
 *   }
 * }
 * }}
 */
abstract class FlinkTestkit {

  val testTimeout = 10.seconds

  def config: Config = ConfigFactory.empty

  implicit class InletTapOps[T: TypeInformation](inlet: CodecInlet[T]) {
    def inletAsTap(inStream: DataStream[T]) = FlinkInletTap(inlet.name, inStream)
  }

  implicit class OutletTapOps[T: TypeInformation](outlet: CodecOutlet[T]) {
    def outletAsTap = FlinkOutletTap(outlet.name)
  }

  // tap inlet port to take a source function directly
  def inletAsTap[In: TypeInformation](in: CodecInlet[In], inStream: DataStream[In]): FlinkInletTap[In] = in.inletAsTap(inStream)

  // Java API
  def getInletAsTap[In](in: CodecInlet[In], inStream: JDataStream[In], clazz: Class[In]): FlinkInletTap[In] =
    inletAsTap(in, new DataStream(inStream))(TypeInformation.of[In](clazz))

  // tap outlet port for query result
  def outletAsTap[Out: TypeInformation](out: CodecOutlet[Out]): FlinkOutletTap[Out] = out.outletAsTap

  // Java API
  def getOutletAsTap[Out](out: CodecOutlet[Out], clazz: Class[Out]): FlinkOutletTap[Out] = outletAsTap(out)(TypeInformation.of[Out](clazz))

  /**
   * Runs the `flinkStreamlet` using `inletTaps` as the sources and `outletTaps` as the sinks. Based on the port name
   * the appropriate `inletTap` and `outletTap` are selected for building the stream computation graph. This
   * graph is then submitted for execution to Flink runtime.
   *
   * @param flinkStreamlet the Sparklet to run
   * @param inletTaps the collection of inlets
   * @param outletTaps the collection of outlets
   * @param env the stream execution environment where the job will run
   *
   * @return Unit
   */
  def run(
      flinkStreamlet: FlinkStreamlet,
      inletTaps: Seq[FlinkInletTap[_]],
      outletTaps: Seq[FlinkOutletTap[_]],
      env: StreamExecutionEnvironment
  ): Unit = {
    val ctx = new TestFlinkStreamletContext("testFlinkStreamlet", env, inletTaps, outletTaps, config)
    doRun(ctx, flinkStreamlet)
  }

  /**
   * Java API
   *
   * Runs the `flinkStreamlet` using `inletTaps` as the sources and `outletTaps` as the sinks. Based on the port name
   * the appropriate `inletTap` and `outletTap` are selected for building the stream computation graph. This
   * graph is then submitted for execution to Flink runtime.
   *
   * @param flinkStreamlet the Sparklet to run
   * @param inletTaps the collection of inlets
   * @param outletTaps the collection of outlets
   * @param env the stream execution environment where the job will run
   *
   * @return Unit
   */
  def run(
      flinkStreamlet: FlinkStreamlet,
      inletTaps: java.util.List[FlinkInletTap[_]],
      outletTaps: java.util.List[FlinkOutletTap[_]],
      env: JStreamExecutionEnvironment
  ): Unit = {
    val ctx = new TestFlinkStreamletContext(
      "testFlinkStreamlet",
      new StreamExecutionEnvironment(env),
      inletTaps.asScala,
      outletTaps.asScala,
      config
    )
    doRun(ctx, flinkStreamlet)
  }

  private[testkit] def doRun(
      ctx: TestFlinkStreamletContext,
      flinkStreamlet: FlinkStreamlet
  ): Unit = {
    flinkStreamlet.setContext(ctx)
    val res = flinkStreamlet.run(ctx.config)
    Await.ready(res.completed, testTimeout)
    ()
  }
}

case class FlinkInletTap[T: TypeInformation](
    portName: String,
    inStream: DataStream[T]
)

case class FlinkOutletTap[T: TypeInformation](
    portName: String
)
