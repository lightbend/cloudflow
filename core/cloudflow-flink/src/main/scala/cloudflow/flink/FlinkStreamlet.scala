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

import java.nio.file.Path
import org.slf4j.LoggerFactory
import com.typesafe.config.Config

import scala.concurrent.{ Future, Promise }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Failure, Try }

import org.apache.flink.api.common.JobExecutionResult
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.client.program.OptimizerPlanEnvironment
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.datastream.{ DataStreamSink, DataStream ⇒ JDataStream }
import org.apache.flink.streaming.api.environment.CheckpointConfig
import org.apache.flink.streaming.api.scala._

import net.ceedubs.ficus.Ficus._
import cloudflow.streamlets._
import cloudflow.streamlets.BootstrapInfo._

/**
 * The base class for defining Flink streamlets. Derived classes need to override `createLogic` to
 * provide the custom implementation for the behavior of the streamlet.
 *
 * Here's an example:
 * {{{
 *  // new custom `FlinkStreamlet`
 *  class MyFlinkProcessor extends FlinkStreamlet {
 *    // Step 1: Define inlets and outlets. Note for the outlet you can specify
 *    //         the partitioner function explicitly or else `RoundRobinPartitioner`
 *    //         will be used
 *    val in = AvroInlet[Data]("in")
 *    val out = AvroOutlet[Simple]("out", _.name)
 *
 *    // Step 2: Define the shape of the streamlet. In this example the streamlet
 *    //         has 1 inlet and 1 outlet
 *    val shape = StreamletShape(in, out)
 *
 *    // Step 3: Provide custom implementation of `FlinkStreamletLogic` that defines
 *    //         the behavior of the streamlet
 *    override def createLogic() = new FlinkStreamletLogic {
 *      override def executeStreamingQueries = {
 *        val outStream: DataStream[Simple] =
 *          writeStream(
 *            readStream(in).map(r => Simple(r.name)),
 *            out
 *          )
 *        executionEnv.execute()
 *      }
 *    }
 *  }
 * }}}
 */
abstract class FlinkStreamlet extends Streamlet with Serializable {
  @transient lazy val log = LoggerFactory.getLogger(getClass.getName)

  final override val runtime = FlinkStreamletRuntime

  // ctx is always first set by runner through `init` so this is safe.
  @volatile private var ctx: FlinkStreamletContext = null

  private val readyPromise = Promise[Dun]()
  private val completionPromise = Promise[Dun]()
  private val completionFuture = completionPromise.future

  protected final implicit def context: FlinkStreamletContext = {
    if (ctx == null) throw new FlinkStreamletContextException()
    ctx
  }

  /**
   * Java API
   *
   * Returns the [[StreamletContext]] in which this streamlet is run. It can only be accessed when the streamlet is run.
   */
  protected final def getStreamletContext(): FlinkStreamletContext = context

  override protected final def createContext(config: Config): FlinkStreamletContext = {
    (for {
      streamletDefinition ← StreamletDefinition.read(config)
    } yield {
      val updatedConfig = streamletDefinition.config.withFallback(config)
      new FlinkStreamletContextImpl(streamletDefinition, createExecutionEnvironment, updatedConfig)
    })
      .recoverWith {
        case th ⇒ Failure(new Exception(s"Failed to create context from $config", th))
      }.get
  }

  private def createExecutionEnvironment: StreamExecutionEnvironment = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment

    val StartCheckpointIntervalInMillis = 10000
    val ProgressInMillisBetweenCheckpoints = 500
    val CheckpointCompletionTimeLimitInMillis = 60000 // 1 minute

    // start a checkpoint every `StartCheckpointIntervalInMillis` ms
    env.enableCheckpointing(StartCheckpointIntervalInMillis)
    val checkpointConfig = env.getCheckpointConfig
    // set mode to exactly-once (this is the default)
    checkpointConfig.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE)

    // make sure `ProgressInMillisBetweenCheckpoints` ms of progress happen between checkpoints
    checkpointConfig.setMinPauseBetweenCheckpoints(ProgressInMillisBetweenCheckpoints)

    // checkpoints have to complete within one minute, or are discarded
    checkpointConfig.setCheckpointTimeout(CheckpointCompletionTimeLimitInMillis)

    // allow only one checkpoint to be in progress at the same time
    checkpointConfig.setMaxConcurrentCheckpoints(1)

    // enable externalized checkpoints which are retained after job cancellation
    checkpointConfig.enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION)

    env
  }

  /**
   * This method is used to inject a `FlinkStreamletContext` directly instead of through the
   * `Config`. This is used mainly by the testkit to inject the test context
   */
  private[flink] def setContext(streamletContext: FlinkStreamletContext): FlinkStreamlet = {
    ctx = streamletContext
    this
  }

  final class FlinkStreamletContextException() extends Exception("The FlinkStreamletContext can only be accessed from within the streamlet logic.")

  protected def createLogic(): FlinkStreamletLogic

  override final def run(config: Config): StreamletExecution = {
    // create a context only when it is not set
    if (ctx == null) ctx = createContext(config)

    val configStr = getFlinkConfigInfo(ctx.env).foldLeft("\n") {
      case (acc, (k, v)) ⇒
        s"$acc\n$k = $v"
    }

    log.info(s"""
      |\n${box("Flink Config Values")}
      |${configStr}\n
      """.stripMargin
    )

    readyPromise.trySuccess(Dun)
    val localMode = config.as[Option[Boolean]]("cloudflow.local").getOrElse(false)
    if (localMode) LocalFlinkJobExecutor.execute()
    else ClusterFlinkJobExecutor.execute()
  }

  /**
   * Different strategy for execution of Flink jobs in local mode and in cluster
   */
  sealed trait FlinkJobExecutor extends Serializable {
    def execute(): StreamletExecution
  }

  /**
   * Future based execution of Flink jobs on the sandbox
   */
  private case object LocalFlinkJobExecutor extends FlinkJobExecutor {
    def execute(): StreamletExecution = {
      log.info(s"Executing local mode ${ctx.streamletRef}")
      val jobResult = Future(createLogic.executeStreamingQueries(ctx.env))

      new StreamletExecution {
        val readyFuture = readyPromise.future

        def completed: Future[Dun] = {
          jobResult.map(_ ⇒ Dun)
        }
        def ready: Future[Dun] = readyFuture
        def stop(): Future[Dun] = ???
      }
    }
  }

  /**
   * Execution in blocking mode.
   */
  private case object ClusterFlinkJobExecutor extends FlinkJobExecutor {
    def execute(): StreamletExecution = {
      log.info(s"Executing cluster mode ${ctx.streamletRef}")

      Try {
        createLogic.executeStreamingQueries(ctx.env)
      }.fold(
        th ⇒ th match {
          // rethrow for Flink to catch as Flink control flow depends on this
          case pax: OptimizerPlanEnvironment.ProgramAbortException ⇒ throw pax
          case _: Throwable                                        ⇒ completionPromise.tryFailure(th)
        },
        _ ⇒ completionPromise.trySuccess(Dun)
      )

      new StreamletExecution {
        val readyFuture = readyPromise.future

        def completed: Future[Dun] = completionFuture
        def ready: Future[Dun] = readyFuture
        def stop(): Future[Dun] = ???
      }
    }
  }

  override def logStartRunnerMessage(buildInfo: String): Unit = {
    log.info(s"""
      |Initializing Flink Runner ..
      |\n${box("Build Info")}
      |${buildInfo}
      """.stripMargin
    )
  }

  private def getFlinkConfigInfo(env: StreamExecutionEnvironment): Map[String, String] = {
    Map.empty[String, String] +
      ("Parallelism" -> s"${env.getParallelism}") +
      ("Max Parallelism" -> s"${env.getMaxParallelism}") +
      ("Checkpointing enabled" -> s"${env.getJavaEnv.getCheckpointConfig.isCheckpointingEnabled}") +
      ("Checkpointing Mode" -> s"${env.getCheckpointingMode}") +
      ("Checkpoint Interval (millis)" -> s"${env.getJavaEnv.getCheckpointInterval}") +
      ("Checkpoint timeout" -> s"${env.getJavaEnv.getCheckpointConfig.getCheckpointTimeout}") +
      ("Restart Strategy" -> s"${env.getRestartStrategy.getDescription}")
  }

  final def configuredValue(context: FlinkStreamletContext, configKey: String): String = {
    context.streamletConfig.getString(configKey)
  }
}

/**
 * Provides an entry-point for defining the behavior of a FlinkStreamlet.
 * Overide the method `buildExecutionGraph` to build the computation graph that needs
 * to run as part of the business logic for the `FlinkStreamlet`.
 *
 * Here's an example of how to provide a specialized implementation of `FlinkStreamletLogic` as part
 * of implementing a custom `FlinkStreamlet`:
 *
 * {{{
 *    // new custom `FlinkStreamlet`
 *    // define inlets, outlets and shape
 *
 *    // provide custom implementation of `FlinkStreamletLogic`
 *    override def createLogic() = new FlinkStreamletLogic {
 *      override def buildExecutionGraph = {
 *        val ins: DataStream[Data] = readStream(in)
 *        val simples: DataStream[Simple] = ins.map(r ⇒ new Simple(r.getName()))
 *        writeStream(out, simples)
 *      }
 *    }
 *  }
 * }}}
 */
abstract class FlinkStreamletLogic(implicit val context: FlinkStreamletContext) extends StreamletLogic[FlinkStreamletContext] {
  /**
   * Read from the underlying external storage through the inlet `inlet` and return a DataStream
   *
   * @param inlet the inlet port to read from
   * @return the data read as `DataStream[In]`
   */
  final def readStream[In: TypeInformation](inlet: CodecInlet[In]): DataStream[In] =
    context.readStream(inlet)

  /**
   * Java API
   * Read from the underlying external storage through the inlet `inlet` and return a DataStream
   *
   * @param inlet the inlet port to read from
   * @param clazz the class of data flowing from `inlet`
   * @return the data read as `DataStream[In]`
   */
  final def readStream[In](inlet: CodecInlet[In], clazz: Class[In]): JDataStream[In] =
    context.readStream(inlet)(TypeInformation.of[In](clazz))
      .javaStream

  /**
   * Write to the external storage using the outlet `outlet` from the stream `stream`
   * and return the same stream
   *
   * @param outlet the outlet port to write to
   * @param stream the data stream to write
   *
   * @return the result `DataStreamSink[Out]`
   */
  final def writeStream[Out: TypeInformation](outlet: CodecOutlet[Out], stream: DataStream[Out]): DataStreamSink[Out] =
    context.writeStream(outlet, stream)

  /**
   * Java API
   * Write to the external storage using the outlet `outlet` from the stream `stream`
   * and return the same stream
   *
   * @param outlet the outlet port to write to
   * @param stream the data stream to write
   * @param clazz the class of data flowing from `inlet`
   *
   * @return the result `DataStreamSink[Out]`
   */
  final def writeStream[Out](
      outlet: CodecOutlet[Out],
      stream: JDataStream[Out],
      clazz: Class[Out]): DataStreamSink[Out] =
    context.writeStream(outlet, new DataStream(stream))(TypeInformation.of[Out](clazz))

  final def config: Config = context.config

  final def streamletRef: String = context.streamletRef

  /**
   * Derived classes need to override this method to provide a custom implementation of the
   * logic to start execution of queries.
   */
  def buildExecutionGraph(): Unit

  def executeStreamingQueries(env: StreamExecutionEnvironment): JobExecutionResult = {
    buildExecutionGraph()
    env.execute(s"Executing $streamletRef")
  }

  /**
   * The path mounted for a VolumeMount request from a streamlet.
   * In a clustered deployment, the mounted path will correspond to the requested mount path in the
   * [[cloudflow.streamlets.VolumeMount VolumeMount]] definition.
   * In a local environment, this path will be replaced by a local folder.
   * @param volumeMount the volumeMount declaration for which we want to obtain the mounted path.
   * @return the path where the volume is mounted.
   * @throws [[cloudflow.streamlets.MountedPathUnavailableException MountedPathUnavailableException ]] in the case the path is not available.
   */
  final def getMountedPath(volumeMount: VolumeMount): Path = context.getMountedPath(volumeMount)

}

case object FlinkStreamletRuntime extends StreamletRuntime {
  override val name: String = "flink"
}

