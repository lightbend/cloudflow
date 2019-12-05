package cloudflow.examples.frauddetection.ingress

import java.util.UUID

import akka.NotUsed
import akka.stream.scaladsl.Source
import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.examples.frauddetection.data.CustomerTransaction
import cloudflow.streamlets.{ IntegerConfigParameter, StreamletShape }
import cloudflow.streamlets.avro.AvroOutlet
import org.joda.time.{ DateTime, DateTimeZone }
import cloudflowx.config.ConfigUtil
import cloudflowx.config.ConfigUtil.implicits._
import cloudflowx.ingress.RecordsReader

import scala.concurrent.duration._

/**
 * Reads transaction records from a CSV file,
 * parses it into a CustomerTransaction and sends it downstream.
 */
class GenerateTransactions extends AkkaStreamlet {

  //\\//\\//\\ INLETS //\\//\\//\\

  //\\//\\//\\ OUTLETS //\\//\\//\\
  val out = AvroOutlet[CustomerTransaction]("transactions")

  //\\//\\//\\ SHAPE //\\//\\//\\
  final override val shape = StreamletShape(out)

  //\\//\\//\\ LOGIC //\\//\\//\\
  final override def createLogic = new RunnableGraphStreamletLogic {

    def runnableGraph =
      GenerateTransactionsUtil.makeSource()
        .map(transaction ⇒ {
          log.info("Reading Transaction: " + transaction.toString)
          transaction
        })
        .to(plainSink(out))
  }

}

object GenerateTransactionsUtil {

  val rootConfigKey = "fraud-detection"

  lazy val dataFrequencyMilliseconds: FiniteDuration =
    ConfigUtil.default
      .getOrElse[Int](rootConfigKey + ".data-frequency-milliseconds")(1).millisecond

  def makeSource(
      frequency:  FiniteDuration = dataFrequencyMilliseconds,
      configRoot: String         = rootConfigKey): Source[CustomerTransaction, NotUsed] = {
    val reader = makeRecordsReader(configRoot)
    Source.repeat(reader)
      .map(reader ⇒ reader.next()._2) // Only keep the record part of the tuple
      .throttle(1, frequency)
  }

  val DefaultSeparator = ","

  def makeRecordsReader(configRoot: String = rootConfigKey): RecordsReader[CustomerTransaction] =
    RecordsReader.fromConfiguration[CustomerTransaction](
      configurationKeyRoot = configRoot,
      dropFirstN = 1)(parse)

  val parse: String ⇒ Either[String, CustomerTransaction] = line ⇒ {
    val tokens = line.split(DefaultSeparator)
    if (tokens.length < 11) {
      Left(s"Record does not have 11 fields, ${tokens.mkString(DefaultSeparator)}")
    } else try {
      val dtokens = tokens.map(_.trim.toFloat)
      Right(CustomerTransaction(
        time = DateTime.now(DateTimeZone.UTC).getMillis(),
        v1 = dtokens(1),
        v2 = dtokens(2),
        v3 = dtokens(3),
        v4 = dtokens(4),
        v5 = dtokens(5),
        v6 = dtokens(6),
        v7 = dtokens(7),
        v9 = dtokens(9),
        v10 = dtokens(10),
        v11 = dtokens(11),
        v12 = dtokens(12),
        v14 = dtokens(14),
        v16 = dtokens(16),
        v17 = dtokens(17),
        v18 = dtokens(18),
        v19 = dtokens(19),
        v21 = dtokens(21),
        amount = dtokens(29),
        transactionId = UUID.randomUUID().toString,
        customerId = UUID.randomUUID().toString,
        merchantId = UUID.randomUUID().toString
      ))
    } catch {
      case scala.util.control.NonFatal(nf) ⇒
        Left(
          s"Failed to parse string ${tokens.mkString(DefaultSeparator)}. cause: $nf")
    }
  }
}
