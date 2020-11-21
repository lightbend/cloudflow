import Versions._
import sbt._

object Dependencies {
  val slf4jAPI        = "org.slf4j"                       % "slf4j-api"                 % slf4jVersion
  val slf4jLog4J      = "org.slf4j"                       % "slf4j-log4j12"             % slf4jVersion
  val scalaTest       = "org.scalatest"                   %% "scalatest"                % scalaTestVersion
}
