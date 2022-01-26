import sbt._
import sbt.Keys._

enablePlugins(
  CloudflowApplicationPlugin,
  CloudflowAkkaPlugin,
  AkkaGrpcPlugin
)

scalaVersion := "2.12.15"

akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java)
libraryDependencies ++= Seq(
  Cloudflow.library.CloudflowProto,
  "com.typesafe.akka" %% "akka-http2-support" % "10.2.0"
)

ThisBuild / dynverSeparator := "-"
