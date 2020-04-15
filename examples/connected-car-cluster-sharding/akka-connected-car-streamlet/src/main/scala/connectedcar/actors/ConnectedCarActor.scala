package connectedcar.actors

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.cluster.sharding.ShardRegion
import connectedcar.data.{ ConnectedCarAgg, ConnectedCarERecord }

import scala.concurrent.ExecutionContext

object ConnectedCarActor {
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: ConnectedCarERecord ⇒ (msg.carId.toString, msg)
  }

  private val numberOfShards = 100

  val extractShardId: ShardRegion.ExtractShardId = {
    case msg: ConnectedCarERecord ⇒ (msg.carId % numberOfShards).toString
  }
}

class ConnectedCarActor extends Actor with ActorLogging {

  val carId: String = "Car-" + self.path.name
  var driverName: String = null
  var currentSpeed = 0.0
  var averageSpeed = 0.0
  var numberOfRecords = 0

  var treeActor: ActorRef = null
  implicit val ec: ExecutionContext = context.dispatcher

  override def receive: Receive = {
    case record: ConnectedCarERecord ⇒ {
      if (numberOfRecords == 0) {
        driverName = record.driver
        averageSpeed = record.speed
      } else {
        averageSpeed = ((averageSpeed * numberOfRecords) + record.speed) / (numberOfRecords + 1)
      }

      numberOfRecords += 1
      currentSpeed = record.speed

      log.info("Updated CarId: " + carId + " Driver Name: " + driverName + " CarSpeed: " + currentSpeed + " From Actor:" + sender().path)

      sender() ! ConnectedCarAgg(record.carId, record.driver, averageSpeed, numberOfRecords)
    }
  }
}
