package connectedcar.streamlets

import akka.NotUsed
import akka.stream.scaladsl.{ RunnableGraph, Source }
import cloudflow.akkastream.{ AkkaStreamlet, AkkaStreamletLogic }
import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.proto.ProtoOutlet
import connectedcar.data.ConnectedCarERecord

import scala.util.Random
import scala.concurrent.duration._

object RawCarDataGenerator extends AkkaStreamlet {
  val out: ProtoOutlet[ConnectedCarERecord] = ProtoOutlet[ConnectedCarERecord]("out", m ⇒ m.carId.toString)
  val shape: StreamletShape                 = StreamletShape.withOutlets(out)

  override def createLogic: AkkaStreamletLogic = new RunnableGraphStreamletLogic() {

    override def runnableGraph(): RunnableGraph[_] =
      Source
        .repeat(NotUsed)
        .map(_ ⇒ generateCarERecord()) // Only keep the record part of the tuple
        .throttle(1, 1.second)
        .to(plainSink(out))
  }

  case class Driver(carId: Int, driver: String)
  private val drivers = List(
    Driver(10001001, "Duncan"),
    Driver(10001002, "Kiki"),
    Driver(10001003, "Trevor"),
    Driver(10001004, "Jeremy"),
    Driver(10001005, "David"),
    Driver(10001006, "Nolan"),
    Driver(10001007, "Adam"),
    Driver(10001008, "Hywel")
  )

  val status = List("whoosh", "zoom", "vrrroom")

  def randomDriver(): Driver =
    drivers(Random.nextInt(8))

  //normal temp is 90c - 105c
  private def randomTemp() =
    90 + Random.nextInt(16)

  // battery from 1 - 100%
  private def randomBattery() =
    1 + Random.nextInt(100)

  //power consumption, no idea but 120 - 150
  private def randomPowerConsumption() =
    120 + Random.nextInt(31)

  //highway speed 60mph - 90mph
  private def randomSpeed() =
    60 + Random.nextInt(31)

  private def randomStatus() =
    status(Random.nextInt(3))

  def generateCarERecord(): ConnectedCarERecord = {
    val driver = randomDriver()
    ConnectedCarERecord(System.currentTimeMillis,
                        driver.carId,
                        driver.driver,
                        randomBattery(),
                        randomTemp(),
                        randomPowerConsumption(),
                        randomSpeed(),
                        randomStatus())
  }

}
