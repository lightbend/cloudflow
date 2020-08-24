import sbt._
import sbt.Keys._

enablePlugins(
  CloudflowApplicationPlugin,
  CloudflowAkkaPlugin,
)

scalaVersion := "2.12.12"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http2-support" % "10.2.0",
)
