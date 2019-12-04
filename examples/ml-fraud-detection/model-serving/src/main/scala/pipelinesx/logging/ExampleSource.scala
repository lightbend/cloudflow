package pipelinesx.logging

import akka.NotUsed
import akka.stream.scaladsl.Source
import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.avro.AvroOutlet
import cloudflow.streamlets.StreamletShape
import pipelinesx.ingress.RecordsReader
import pipelinesx.config.ConfigUtil
import pipelinesx.config.ConfigUtil.implicits._
import pipelinesx.logging.{ Logger, LoggingUtil }

import scala.concurrent.duration._
import pipelinesx.test.TestData

/**
 * Reads wine records from a CSV file (which actually uses ";" as the separator),
 * parses it into a WineRecord and sends it downstream.
 */
final case object ExampleSource extends AkkaStreamlet {

  val out = AvroOutlet[TestData]("out", _.id.toString)

  final override val shape = StreamletShape(out)

  override final def createLogic = new RunnableGraphStreamletLogic {
    def runnableGraph =
      Source.repeat(NotUsed).map(m ⇒ TestData(0, "bla")).to(plainSink(out))
  }
}
