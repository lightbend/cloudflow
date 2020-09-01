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

package cloudflow.spark

import java.nio.file.Path
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.reflect.runtime.universe._
import scala.util.{ Failure, Try }
import akka.actor.{ ActorSystem, Cancellable, Scheduler }
import com.typesafe.config.Config
import org.apache.spark.SparkConf
import org.apache.spark.sql.streaming.{ OutputMode, StreamingQuery, Trigger }
import org.apache.spark.sql.{ Dataset, Encoder, SparkSession }
import cloudflow.streamlets.BootstrapInfo._
import cloudflow.streamlets._

/**
 * The base class for defining Spark streamlets. Derived classes need to override `createLogic` to
 * provide the custom implementation for the behavior of the streamlet.
 *
 * Here's an example:
 * {{{
 *  // new custom `SparkStreamlet`
 *  object MySparkProcessor extends SparkStreamlet {
 *    // Step 1: Define inlets and outlets. Note for the outlet you need to specify
 *    //         the partitioner function explicitly
 *    val in = AvroInlet[Data]("in")
 *    val out = AvroOutlet[Simple]("out", _.name)
 *
 *    // Step 2: Define the shape of the streamlet. In this example the streamlet
 *    //         has 1 inlet and 1 outlet
 *    val shape = StreamletShape(in, out)
 *
 *    // Step 3: Provide custom implementation of `SparkStreamletLogic` that defines
 *    //         the behavior of the streamlet
 *    override def createLogic() = new SparkStreamletLogic {
 *      override def buildStreamingQueries = {
 *        val dataset = readStream(in)
 *        val outStream = dataset.select($"name").as[Simple]
 *        val query = writeStream(outStream, out, OutputMode.Append)
 *        Seq(query)
 *      }
 *    }
 *  }
 * }}}
 */
trait SparkStreamlet extends Streamlet[SparkStreamletContext] with Serializable {
  final override val runtime = SparkStreamletRuntime
  val StopTimeout            = 30.seconds

  override protected final def createContext(config: Config): SparkStreamletContext =
    (for {
      streamletConfig ← StreamletDefinition.read(config)
      session         ← makeSparkSession(makeSparkConfig)
    } yield {
      val updatedConfig = streamletConfig.config.withFallback(config)
      new kafka.SparkStreamletContextImpl(streamletConfig, session, updatedConfig)
    }).recoverWith {
      case th ⇒ Failure(new Exception(s"Failed to create context from $config", th))
    }.get

  protected def createLogic(): SparkStreamletLogic

  override final def run(context: SparkStreamletContext): StreamletExecution = {
    val completionPromise = Promise[Dun]()
    val completionFuture  = completionPromise.future

    // these values are candidates to move to the configuration system
    val InitialDelay                 = 2 seconds
    val MonitorFrequency             = 5 seconds
    implicit val system: ActorSystem = ActorSystem("spark_streamlet", context.config)

    val streamletQueryExecution = createLogic.buildStreamingQueries

    new StreamletExecution {

      // schedule a function to check periodically if any of the queries stopped
      val scheduledQueryCheck: Cancellable = system.scheduler.scheduleWithFixedDelay(InitialDelay, MonitorFrequency) {
        new Runnable() {
          override def run(): Unit = {
            val someQueryStopped = streamletQueryExecution.queries.exists(!_.isActive)
            if (someQueryStopped) completionPromise.completeWith(stop())
          }
        }
      }

      // this future will be successful when any of the queries face an exception
      // or is stopped. The runner needs to await on this future and exit only when it
      // succeeds
      def completed: Future[Dun] = completionFuture

      val ready: Future[Dun] = Future.successful(Dun)

      def stop(): Future[Dun] = {
        streamletQueryExecution.stop()
        scheduledQueryCheck.cancel()
        poll(streamletQueryExecution.queries.forall(!_.isActive), 1.second, StopTimeout, system.scheduler)
          .recoverWith {
            case ex: TimeoutException =>
              val hangingQueries = streamletQueryExecution.queries.map(_.name).mkString(",")
              Future.failed(new TimeoutException(s"Could not terminate queries [$hangingQueries]. Reason: ${ex.getMessage}"))
          }
          .map { _ =>
            val exceptions = streamletQueryExecution.queries.flatMap(_.exception.map(_.cause))
            if (exceptions.nonEmpty) {
              // fail the future with a list of exceptions returned by the queries
              throw ExceptionAcc(exceptions)
            } else {
              Dun
            }
          }
      }

      private def poll(predicate: => Boolean, frequency: FiniteDuration, deadline: FiniteDuration, s: Scheduler): Future[Unit] = {
        val times = Math.ceil(deadline / frequency).toInt
        def _poll(count: Int): Future[Unit] = (predicate, count <= 0) match {
          case (true, _) => Future.successful(())
          case (_, true) => Future.failed(new TimeoutException(s"Poll timed out after ${deadline.toMillis} millis"))
          case _ =>
            val p = Promise[Unit]()
            s.scheduleOnce(frequency) { p.completeWith(_poll(count - 1)) }
            p.future
        }
        _poll(times)
      }
    }
  }

  override def logStartRunnerMessage(buildInfo: String): Unit =
    log.info(s"""
      |Initializing Spark Runner ..
      |\n${box("Build Info")}
      |${buildInfo}
      """.stripMargin)

  final def configuredValue(context: SparkStreamletContext, configKey: String): String =
    context.streamletConfig.getString(configKey)

  private def makeSparkConfig(): SparkConf = {
    val conf   = new SparkConf()
    val master = conf.getOption("spark.master").getOrElse("local[2]")
    conf
      .setMaster(master)
      // arbitrary number - default is 200 which is quite large for our sample apps
      // Needs to take a value passed through configuration, in case the user would like to override this.
      .set("spark.sql.shuffle.partitions", "20")
      .set("spark.sql.codegen.wholeStage", "false")
      .set("spark.shuffle.compress", "false")
  }

  private def makeSparkSession(sparkConfig: SparkConf): Try[SparkSession] = Try {
    val session = SparkSession
      .builder()
      .appName(applicationName)
      .config(sparkConfig)
      .getOrCreate()
    session.sparkContext.setLogLevel("WARN")
    session
  }

  val applicationName = "cloudflow-runner-spark"
}

/**
 * Provides an entry-point for defining the behavior of a SparkStreamlet.
 * Overide the method `buildStreamingQueries` to build the collection of `StreamingQuery` that needs
 * to run as part of the business logic for the `SparkStreamlet`.
 *
 * Here's an example of how to provide a specialized implementation of `SparkStreamletLogic` as part
 * of implementing a custom `SparkStreamlet`:
 *
 * {{{
 *  // new custom `SparkStreamlet`
 *  object MySparkProcessor extends SparkStreamlet {
 *    // define inlets, outlets and shape
 *
 *    // provide custom implementation of `SparkStreamletLogic`
 *    override def createLogic() = new SparkStreamletLogic {
 *      override def buildStreamingQueries = {
 *        val dataset = readStream(in)
 *        val outStream = dataset.select($"name").as[Simple]
 *        val query = writeStream(outStream, out, OutputMode.Append)
 *        Seq(query)
 *      }
 *    }
 *  }
 * }}}
 */
abstract class SparkStreamletLogic(implicit val context: SparkStreamletContext) extends StreamletLogic[SparkStreamletContext] {

  override def getContext(): SparkStreamletContext = super.getContext()

  implicit class StreamingQueryExtensions(val query: StreamingQuery) {
    def toQueryExecution: StreamletQueryExecution = StreamletQueryExecution(query)
  }

  /**
   * Read from inlet to generate a `Dataset`.
   */
  final def readStream[In](inPort: CodecInlet[In])(implicit encoder: Encoder[In], typeTag: TypeTag[In]): Dataset[In] =
    context.readStream(inPort)

  /**
   * Write a `StreamingQuery` into outlet using the specified `OutputMode`
   */
  final def writeStream[Out](stream: Dataset[Out],
                             outPort: CodecOutlet[Out],
                             outputMode: OutputMode,
                             optionalTrigger: Option[Trigger] = None)(
      implicit encoder: Encoder[Out],
      typeTag: TypeTag[Out]
  ): StreamingQuery = context.writeStream(stream, outPort, outputMode, optionalTrigger)

  final def config: Config = context.config

  final def session: SparkSession = context.session

  final def streamletRef: String = context.streamletRef

  /**
   * Derived classes need to override this method to provide a custom implementation of the
   * logic to build a `StreamletQueryExecution` object containing one or more `StreamingQuery`s that need to be executed.
   */
  def buildStreamingQueries: StreamletQueryExecution

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

case object SparkStreamletRuntime extends StreamletRuntime {
  override val name: String = "spark"
}

// Allows the management of an executing Streamlet instance
case class StreamletQueryExecution(queries: Vector[StreamingQuery]) {
  final def stop(): Unit = queries.foreach(_.stop)
}

object StreamletQueryExecution {
  def apply(singleQuery: StreamingQuery): StreamletQueryExecution = StreamletQueryExecution(Vector(singleQuery))
  def apply(oneQuery: StreamingQuery, moreQueries: StreamingQuery*): StreamletQueryExecution =
    StreamletQueryExecution(oneQuery +: moreQueries.toVector)
  def apply(querySeq: scala.collection.Seq[StreamingQuery]): StreamletQueryExecution = StreamletQueryExecution(querySeq.toVector)
}
