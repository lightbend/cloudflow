package swissknife.akka

import scala.concurrent.duration._
import akka.NotUsed
import akka.stream.scaladsl.Source
import cloudflow.akkastream.AkkaStreamlet
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroOutlet
import swissknife.data.Data

final class DataGenerator extends AkkaStreamlet {
  val out                  = AvroOutlet[Data]("out", d ⇒ d.src)
  final override val shape = StreamletShape.withOutlets(out)
  override final def createLogic = new RunnableGraphStreamletLogic {
    def runnableGraph = DataGenerator.makeSource().to(plainSink(out))
  }
}

object DataGenerator {
  val OncePerSecond: FiniteDuration = 1.second
  var counter                       = 0
  def makeSource(frequency: FiniteDuration = OncePerSecond): Source[Data, NotUsed] =
    Source
      .tick(0.second, frequency, ())
      .map { _ ⇒
        val data = Data(src = "origin", timestamp = System.currentTimeMillis, count = counter, payload = "")
        counter = counter + 1
        data
      }
      .mapMaterializedValue(_ => NotUsed)
}
