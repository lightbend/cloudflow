import sbt._
import sbt.Keys._

enablePlugins(
  CloudflowApplicationPlugin,
  CloudflowAkkaPlugin,
  AkkaGrpcPlugin
)

scalaVersion := "2.13.8"

akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java)
libraryDependencies ++= Seq(
  Cloudflow.library.CloudflowProto,
  "com.typesafe.akka" %% "akka-http2-support" % "10.2.0"
)

ThisBuild / dynverSeparator := "-"
