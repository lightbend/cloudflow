package cloudflow.akkastreamsdoc

import cloudflow.streamlets._
import cloudflow.akkastream._

object ConfigCustomValidation extends AkkaStreamlet {

  //tag::definition[]
  val militaryTimeParameter =
    RegExpConfigParameter(
      "time",
      "This parameter type validates that the users enter the time in 24h format.",
      "^(0[0-9]|1[0-9]|2[0-3]|[0-9]):[0-5][0-9]$",
      Some("08:00")
    )
  //end::definition[]

  override def configParameters = Vector(militaryTimeParameter)

  val shape = ???

  def createLogic = ???
}
