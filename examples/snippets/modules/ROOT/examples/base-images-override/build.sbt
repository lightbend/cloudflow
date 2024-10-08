import sbt._
import sbt.Keys._

    //tag::docs-projectSetup-example[]
lazy val sampleApp = (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin)
    .settings(
      cloudflowDockerBaseImage := "myRepositoryUrl/myRepositoryPath:adoptopenjdk/openjdk11:alpine",
    //end::docs-projectSetup-example[]
      name := "sample-app",
      organization := "com.lightbend.cloudflow",
      scalaVersion := "2.12.15",
      libraryDependencies ++= Seq(
        "com.lightbend.akka"     %% "akka-stream-alpakka-file"  % "1.1.2",
        "com.typesafe.akka"      %% "akka-http-spray-json"      % "10.1.12",
        "ch.qos.logback"         %  "logback-classic"           % "1.2.11",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.1.12" % "test",
        "org.scalatest"          %% "scalatest"                 % "3.0.8"  % "test"
      )
    )
