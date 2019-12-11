package modelserving.wine

import akka.NotUsed
import akka.stream.scaladsl.Source
import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroOutlet
import modelserving.wine.avro._

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

/**
 * Generates data for recommendations. Every second it
 * loads and sends downstream one record that is randomly generated.
 */
final class WineRecordGenerator extends AkkaStreamlet {

  // Output
  val out = AvroOutlet[WineRecord]("out", _.datatype)

  // Shape
  final override val shape = StreamletShape.withOutlets(out)

  // Create Logic
  override final def createLogic = new RunnableGraphStreamletLogic {
    // Runnable graph
    def runnableGraph =
      WineRecordGeneratorUtils.makeSource().to(plainSink(out))
  }
}

object WineRecordGeneratorUtils {
  // Data frequency
  lazy val dataFrequencyMilliseconds: FiniteDuration = 2.second

  // Make source
  def makeSource(frequency: FiniteDuration = dataFrequencyMilliseconds): Source[WineRecord, NotUsed] = {
    Source.repeat(WineRecordGenerator)
      .map(gen ⇒ gen.getRecord())
      .throttle(1, frequency)
  }
}

// Request record generator
private object WineRecordGenerator {

  val recordList = new ListBuffer[WineRecord]
  val stream = getClass.getResourceAsStream("/wine/data/winequality_red.csv")
  val bufferedSource = scala.io.Source.fromInputStream(stream)
  for (line ← bufferedSource.getLines) {
    val cols = line.split(";").map(_.trim)
    recordList += WineRecord(
      fixed_acidity = cols(0).toDouble,
      volatile_acidity = cols(1).toDouble,
      citric_acid = cols(2).toDouble,
      residual_sugar = cols(3).toDouble,
      chlorides = cols(4).toDouble,
      free_sulfur_dioxide = cols(5).toDouble,
      total_sulfur_dioxide = cols(6).toDouble,
      density = cols(7).toDouble,
      pH = cols(8).toDouble,
      sulphates = cols(9).toDouble,
      alcohol = cols(10).toDouble
    )
  }
  bufferedSource.close
  var iterator = recordList.toIterator

  def getRecord(): WineRecord = {
    if (!iterator.hasNext)
      iterator = recordList.toIterator
    iterator.next()
  }
}

