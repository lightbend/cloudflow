package connectedcar.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import connectedcar.data.{ConnectedCarAgg, ConnectedCarERecord}

case class ConnectedCarERecordWrapper(record: ConnectedCarERecord, sender:ActorRef[ConnectedCarAgg])

object ConnectedCarActor {
  def apply(carId:String): Behavior[ConnectedCarERecordWrapper] = {
    def updated(numberOfRecords: Int, driverName: String, carId:String, averageSpeed: Double, currentSpeed: Double): Behavior[ConnectedCarERecordWrapper] = {
      Behaviors.receive { (context, message) => {
          context.log.info("Update Received- CarId: {} MessageCarId: {} From Actor: {}", 
            carId, message.record.carId, message.sender.path)

          val newAverage = ((averageSpeed * numberOfRecords) + message.record.speed) / (numberOfRecords + 1)
          val newNumberOfRecords = numberOfRecords+1

          val newAgg = ConnectedCarAgg(message.record.carId, message.record.driver, newAverage, newNumberOfRecords)

          message.sender ! newAgg

          updated(newNumberOfRecords, message.record.driver, carId, newAverage, message.record.speed)
        }
      }
    }

    updated(0, "", carId, 0, 0.0)
  }
}
