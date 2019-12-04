package pipelinesx.egress

import akka.stream.scaladsl.Sink
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._

/**
 * An abstraction for the logic in an "Egress" that has a single inlet and then
 * "disposes" of the data in some way that's transparent to Pipelines, e.g.,
 * logs it or writes it to the console.
 * Note that Akka Streams at-most once semantics are used, so don't use this where
 * at-least once is needed, e.g., writing data to a database.
 */
abstract class FlowEgressLogic[IN](
    val inlet: CodecInlet[IN])(
    implicit
    context: AkkaStreamletContext)
  extends RunnableGraphStreamletLogic {
  /**
   * Logic to process the data, such as writing to a database.
   * Note that Akka Streams at-least once semantics are used, so implementations
   * may need to perform deduplication.
   */
  def write(record: IN): Unit

  def runnableGraph =
    plainSource(inlet).to(Sink.foreach(write))
}
